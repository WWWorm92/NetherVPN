package transport

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"log"
	"net"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/WWWorm92/NetherVPN/internal/session"
	"github.com/flynn/noise"
)

const (
	wireInit byte = 0xA1
	wireResponse byte = 0xA2
	sessionParametersSize = 10
)
var ErrHandshakeFrame = errors.New("transport: invalid handshake frame")

// DialHandshake performs a Noise IK exchange over UDP with bounded retransmission.
// Production replay/DoS defenses are NOT implemented. Call only in a lab.
func DialHandshake(ctx context.Context, conn *net.UDPConn, peer *net.UDPAddr, identity noise.DHKey, pinnedServer []byte) (*session.Session, error) {
	initiator, err := handshake.NewInitiator(identity, pinnedServer)
	if err != nil { return nil, err }
	first, err := initiator.Start()
	if err != nil { return nil, err }
	if deadline, ok := ctx.Deadline(); ok {
		if err := conn.SetDeadline(deadline); err != nil { return nil, err }
		defer conn.SetDeadline(time.Time{})
	}
	frame := append([]byte{wireInit}, first...)
	buffer := make([]byte, 4096)
	// Retain the same initiator state and first frame when retrying.
	for {
		if err := ctx.Err(); err != nil { return nil, err }
		if _, err := conn.WriteToUDP(frame, peer); err != nil { return nil, err }
		readUntil := time.Now().Add(time.Second)
		if deadline, ok := ctx.Deadline(); ok && deadline.Before(readUntil) { readUntil = deadline }
		if err := conn.SetReadDeadline(readUntil); err != nil { return nil, err }
		for {
			n, remote, err := conn.ReadFromUDP(buffer)
			if ne, ok := err.(net.Error); ok && ne.Timeout() { break }
			if err != nil { return nil, err }
			if !remote.IP.Equal(peer.IP) || remote.Port != peer.Port || n < 2 || buffer[0] != wireResponse { continue }
			params, out, in, err := initiator.FinishWithPayload(buffer[1:n])
			if err != nil { return nil, err }
			if len(params) != sessionParametersSize { return nil, ErrHandshakeFrame }
			id := binary.BigEndian.Uint64(params[:8])
			epoch := binary.BigEndian.Uint16(params[8:])
			return session.New(out, in, id, epoch)
		}
	}
}

// AcceptHandshake handles exactly one UDP Noise IK initiation. Applications
// MUST add a bounded replay cache, retries and rate limits before deployment.
func AcceptHandshake(conn *net.UDPConn, identity noise.DHKey, authorize func([]byte) bool) (*session.Session, *net.UDPAddr, error) {
	buffer := make([]byte, 4096)
	for {
		n, peer, err := conn.ReadFromUDP(buffer)
		if err != nil { return nil, nil, err }
		if n < 2 || buffer[0] != wireInit { continue }
		responder, err := handshake.NewResponder(identity)
		if err != nil { return nil, nil, err }
		var params [sessionParametersSize]byte
		if _, err := rand.Read(params[:8]); err != nil { return nil, nil, err }
		binary.BigEndian.PutUint16(params[8:], 1)
		id := binary.BigEndian.Uint64(params[:8])
		if id == 0 { continue }
		response, out, in, err := responder.RespondWithPayload(buffer[1:n], authorize, params[:])
		if err != nil { log.Printf("handshake: rejected initiation from %s: %v",peer,err); continue }
		s, err := session.New(out, in, id, 1)
		if err != nil { return nil, nil, err }
		if _, err := conn.WriteToUDP(append([]byte{wireResponse}, response...), peer); err != nil { return nil, nil, err }
		return s, peer, nil
	}
}
