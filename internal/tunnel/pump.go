package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/session"
)

var ErrMissingPeer = errors.New("tunnel: peer or session missing")

// PumpToUDP reads packets until cancellation, a device error, or a policy
// rejection. The caller must close the reader to interrupt a blocked Read.
func PumpToUDP(ctx context.Context, device io.Reader, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, sourcePolicy func([]byte) error) error {
	if device == nil || conn == nil || peer == nil || secure == nil || sourcePolicy == nil {
		return ErrMissingPeer
	}
	if mtu < 40 || mtu > 9000 { return ErrOversizedPacket }
	buf := make([]byte, mtu+1)
	for {
		if err := ctx.Err(); err != nil { return err }
		n, err := device.Read(buf)
		if err != nil { return err }
		packet := buf[:n]
		if n > mtu { return ErrOversizedPacket }
		if !IsIP(packet) { return ErrInvalidPacket }
		if err := sourcePolicy(packet); err != nil { return err }
		encrypted, err := secure.Seal(packet)
		if err != nil { return err }
		if _, err := conn.WriteToUDP(encrypted, peer); err != nil { return err }
	}
}

// PumpFromUDP receives authenticated packets until cancellation. A socket
// deadline enables bounded cancellation even when the peer is silent.
// No roaming or multi-client dispatch is implemented here.
func PumpFromUDP(ctx context.Context, device io.Writer, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, sourcePolicy func([]byte) error) error {
	if device == nil || conn == nil || peer == nil || secure == nil || sourcePolicy == nil {
		return ErrMissingPeer
	}
	if mtu < 40 || mtu > 9000 { return ErrOversizedPacket }
	buf := make([]byte, mtu+128)
	for {
		if err := ctx.Err(); err != nil { return err }
		if err := conn.SetReadDeadline(time.Now().Add(250 * time.Millisecond)); err != nil { return err }
		n, addr, err := conn.ReadFromUDP(buf)
		if ne, ok := err.(net.Error); ok && ne.Timeout() { continue }
		if err != nil { return err }
		if !addr.IP.Equal(peer.IP) || addr.Port != peer.Port { continue }
		packet, err := DecodeIP(secure, buf[:n], mtu)
		if err != nil { continue }
		if err := sourcePolicy(packet); err != nil { continue }
		nw, err := device.Write(packet)
		if err != nil { return err }
		if nw != len(packet) { return io.ErrShortWrite }
	}
}
