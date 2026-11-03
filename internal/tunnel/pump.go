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

// PumpToUDP is a strict fail-fast pump used by low-level tests and tools.
// Any malformed/unauthorized outbound packet is reported as an error.
func PumpToUDP(ctx context.Context, device io.Reader, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, sourcePolicy func([]byte) error) error {
    return pumpToUDP(ctx, device, conn, peer, secure, mtu, sourcePolicy, false, nil)
}

// PumpToUDPFiltered is for a long-running VPN client. Untrusted input from the
// kernel TUN (including IPv6 when only IPv4 is supported, oversized datagrams,
// and spoofed source addresses) is discarded instead of terminating the
// authenticated session. It never encrypts or transmits a rejected packet.
//
// Fatal I/O and cryptographic errors still terminate the pump so the caller
// can reconnect. The callback is for bounded diagnostics, not raw packet logs.
func PumpToUDPFiltered(ctx context.Context, device io.Reader, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, sourcePolicy func([]byte) error, onDrop func(reason error, bytes int)) error {
    return pumpToUDP(ctx, device, conn, peer, secure, mtu, sourcePolicy, true, onDrop)
}

func pumpToUDP(ctx context.Context, device io.Reader, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, sourcePolicy func([]byte) error, dropRejected bool, onDrop func(error, int)) error {
    if device == nil || conn == nil || peer == nil || secure == nil || sourcePolicy == nil {
        return ErrMissingPeer
    }
    if mtu < 40 || mtu > 9000 {
        return ErrOversizedPacket
    }

    buf := make([]byte, mtu+1)
    reject := func(err error, n int) error {
        if !dropRejected {
            return err
        }
        if onDrop != nil {
            onDrop(err, n)
        }
        return nil
    }

    for {
        if err := ctx.Err(); err != nil {
            return err
        }
        n, err := device.Read(buf)
        if err != nil {
            return err
        }
        if n < 0 || n > len(buf) {
            return ErrInvalidPacket // Invalid Reader contract, not a network packet.
        }
        packet := buf[:n]
        if n > mtu {
            if err := reject(ErrOversizedPacket, n); err != nil {
                return err
            }
            continue
        }
        if !IsIP(packet) {
            if err := reject(ErrInvalidPacket, n); err != nil {
                return err
            }
            continue
        }
        if err := sourcePolicy(packet); err != nil {
            if failure := reject(err, n); failure != nil {
                return failure
            }
            continue
        }
        encrypted, err := secure.Seal(packet)
        if err != nil {
            return err
        }
        if _, err := conn.WriteToUDP(encrypted, peer); err != nil {
            return err
        }
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
