// Package tunnel forwards bounded IPv4/IPv6 packets between a TUN-like
// interface and an already-authenticated encrypted datagram session.
package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/session"
)

const DefaultMTU = 1280

var (
	ErrInvalidPacket = errors.New("tunnel: invalid IP packet")
	ErrOversizedPacket = errors.New("tunnel: IP packet exceeds configured MTU")
)

// IsIP verifies a basic L3 packet envelope; it is not a firewall or a
// replacement for client-IP authorization and anti-spoofing rules.
func IsIP(packet []byte) bool {
	if len(packet) < 1 { return false }
	switch packet[0] >> 4 {
	case 4:
		if len(packet) < 20 { return false }
		ihl := int(packet[0]&0x0f)*4
		if ihl < 20 || len(packet) < ihl { return false }
		total := int(packet[2])<<8 | int(packet[3])
		return total >= ihl && total == len(packet)
	case 6:
		if len(packet) < 40 { return false }
		size := 40 + (int(packet[4]) << 8) + int(packet[5])
		return size == len(packet)
	default:
		return false
	}
}

// EncodeIP validates a packet before encrypting it. Both ends must already
// share an authenticated session from the Noise IK handshake.
func EncodeIP(secure *session.Session, packet []byte, mtu int) ([]byte,error) {
	if mtu < 40 || mtu > 9000 { return nil, ErrOversizedPacket }
	if len(packet)>mtu { return nil,ErrOversizedPacket }
	if !IsIP(packet) { return nil,ErrInvalidPacket }
	return secure.Seal(packet)
}

// DecodeIP authenticates and replay-checks the datagram before validating
// its IP envelope. It does not verify source address ownership.
func DecodeIP(secure *session.Session, datagram []byte, mtu int) ([]byte,error) {
	packet,err:=secure.Open(datagram)
	if err!=nil { return nil,err }
	if len(packet)>mtu { return nil,ErrOversizedPacket }
	if !IsIP(packet) { return nil,ErrInvalidPacket }
	return packet,nil
}

// SendOne reads a single IP packet from a TUN-like reader and sends it to a
// known UDP peer. Route and DNS configuration are deliberately out of scope.
func SendOne(tun io.Reader, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int) error {
	if mtu<40 || mtu>9000 { return ErrOversizedPacket }
	packet:=make([]byte,mtu+1)
	n,err:=tun.Read(packet)
	if err!=nil { return err }
	data,err:=EncodeIP(secure,packet[:n],mtu)
	if err!=nil { return err }
	_,err=conn.WriteToUDP(data,peer)
	return err
}

// ReceiveOne accepts a valid encrypted UDP packet from the pinned endpoint,
// then writes its decrypted IP packet to a TUN-like writer. This laboratory
// API does not implement roaming, cancellation or multi-client routing.
func ReceiveOne(ctx context.Context, tun io.Writer, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int) error {
	if deadline,ok:=ctx.Deadline();ok {
		if err:=conn.SetReadDeadline(deadline);err!=nil { return err }
		defer conn.SetReadDeadline(time.Time{})
	}
	buffer:=make([]byte,mtu+128)
	for {
		if err:=ctx.Err();err!=nil{return err}
		n,source,err:=conn.ReadFromUDP(buffer)
		if err!=nil{return err}
		if !source.IP.Equal(peer.IP)||source.Port!=peer.Port { continue }
		packet,err:=DecodeIP(secure,buffer[:n],mtu)
		if err!=nil { continue }
		written,err:=tun.Write(packet)
		if err!=nil{return err}
		if written!=len(packet){return io.ErrShortWrite}
		return nil
	}
}
