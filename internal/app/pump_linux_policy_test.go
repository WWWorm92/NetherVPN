//go:build linux

package app

import (
 "errors"
 "net/netip"
 "testing"

 "github.com/WWWorm92/NetherVPN/internal/tunnel"
)

func TestClientOutboundPolicyDoesNotAllowIPv6OrSpoofing(t *testing.T) {
 local:=netip.MustParseAddr("10.77.0.3")
 accepted:=make([]byte,20)
 accepted[0]=0x45
 accepted[3]=20
 copy(accepted[12:16],local.AsSlice())

 if err:=clientOutboundPolicy(accepted,local,true);err!=nil {
  t.Fatalf("valid IPv4 assigned to client rejected: %v",err)
 }
 if err:=clientOutboundPolicy(accepted,local,false);err!=nil {
  t.Fatalf("split tunnel incorrectly rejected assigned IPv4: %v",err)
 }

 spoofed:=append([]byte(nil),accepted...)
 spoofed[15]=4
 if err:=clientOutboundPolicy(spoofed,local,true);!errors.Is(err,tunnel.ErrUnauthorizedIP){
  t.Fatalf("spoofed client source was not rejected: %v",err)
 }

 ipv6:=make([]byte,40)
 ipv6[0]=0x60
 if err:=clientOutboundPolicy(ipv6,local,true);!errors.Is(err,errIPv6Blocked){
  t.Fatalf("full tunnel must drop IPv6 without reconnecting: %v",err)
 }
 if err:=clientOutboundPolicy([]byte{0x45},local,true);!errors.Is(err,tunnel.ErrInvalidPacket){
  t.Fatalf("malformed IP must be dropped: %v",err)
 }
}
