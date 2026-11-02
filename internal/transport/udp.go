// Package transport provides a minimal UDP datagram transport. It does not
// perform authentication, handshake, routing or TUN configuration.
package transport

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/session"
)

const MaxDatagramSize = 65507

var ErrDatagramTooLarge = errors.New("transport: datagram exceeds UDP limit")

// Exchange encrypts a payload over UDP and authenticates the reply.
// Both peers must already possess a mutually authenticated session.
func Exchange(ctx context.Context, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, payload []byte) ([]byte, error) {
	packet, err := secure.Seal(payload)
	if err != nil {
		return nil, err
	}
	if len(packet) > MaxDatagramSize {
		return nil, ErrDatagramTooLarge
	}
	if deadline, ok := ctx.Deadline(); ok {
		if err := conn.SetDeadline(deadline); err != nil {
			return nil, err
		}
		defer conn.SetDeadline(time.Time{})
	}
	if _, err := conn.WriteToUDP(packet, peer); err != nil {
		return nil, err
	}
	buffer := make([]byte, MaxDatagramSize)
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		n, from, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return nil, err
		}
		if !from.IP.Equal(peer.IP) || from.Port != peer.Port {
			continue
		}
		plain, err := secure.Open(buffer[:n])
		if err != nil {
			continue
		}
		return plain, nil
	}
}

// ServeOne receives a single authenticated datagram and sends an encrypted
// response to its source. It is a test building block, not a production server.
func ServeOne(conn *net.UDPConn, secure *session.Session, handler func([]byte) []byte) error {
	buffer := make([]byte, MaxDatagramSize)
	for {
		n, peer, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return err
		}
		plain, err := secure.Open(buffer[:n])
		if err != nil {
			continue
		}
		reply, err := secure.Seal(handler(plain))
		if err != nil {
			return err
		}
		if len(reply) > MaxDatagramSize {
			return ErrDatagramTooLarge
		}
		_, err = conn.WriteToUDP(reply, peer)
		return err
	}
}
