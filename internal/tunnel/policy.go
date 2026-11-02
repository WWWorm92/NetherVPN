package tunnel

import (
	"errors"
	"net/netip"
)

var ErrUnauthorizedIP = errors.New("tunnel: source IP not assigned to client")

// SourceIP extracts a source address from an already validated IP datagram.
// It intentionally does not support IPv6 jumbograms or IPv4 fragments.
func SourceIP(packet []byte) (netip.Addr, error) {
	if !IsIP(packet) { return netip.Addr{}, ErrInvalidPacket }
	switch packet[0] >> 4 {
	case 4:
		var source [4]byte
		copy(source[:], packet[12:16])
		return netip.AddrFrom4(source), nil
	case 6:
		var source [16]byte
		copy(source[:], packet[8:24])
		return netip.AddrFrom16(source), nil
	default:
		return netip.Addr{}, ErrInvalidPacket
	}
}

// VerifySource forbids a client from injecting packets with another client's
// VPN address. The assigned address must originate from trusted server policy.
func VerifySource(packet []byte, assigned netip.Addr) error {
	if !assigned.IsValid() || assigned.IsUnspecified() { return ErrUnauthorizedIP }
	source, err := SourceIP(packet)
	if err != nil { return err }
	if source != assigned { return ErrUnauthorizedIP }
	return nil
}

 
// DestinationIP identifies the client assigned to a return packet read from
// the gateway TUN. Only validated IP datagrams are accepted.
func DestinationIP(packet []byte) (netip.Addr,error) {
 if !IsIP(packet){return netip.Addr{},ErrInvalidPacket}
 switch packet[0]>>4{
 case 4:
  var dst [4]byte
  copy(dst[:],packet[16:20])
  return netip.AddrFrom4(dst),nil
 case 6:
  var dst [16]byte
  copy(dst[:],packet[24:40])
  return netip.AddrFrom16(dst),nil
 default:return netip.Addr{},ErrInvalidPacket
 }
}
