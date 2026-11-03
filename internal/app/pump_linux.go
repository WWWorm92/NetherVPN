//go:build linux
package app

import (
 "bytes"
 "context"
 "errors"
 "io"
 "net"
 "net/netip"
 "time"

 "github.com/WWWorm92/NetherVPN/internal/session"
 "github.com/WWWorm92/NetherVPN/internal/tun"
 "github.com/WWWorm92/NetherVPN/internal/tunnel"
)

var errPeerSilent=errors.New("client: peer keepalive timeout")
var keepaliveRequest=[]byte("\x00NVPN-PING-v1")
var keepaliveReply=[]byte("\x00NVPN-PONG-v1")

// runPumps actively checks authenticated peer liveness, not TUN presence.
func runPumps(parent context.Context, device *tun.Device, conn *net.UDPConn, peer *net.UDPAddr, secure *session.Session, mtu int, allowed netip.Addr,local netip.Addr,fullTunnel bool) error {
 ctx,cancel:=context.WithCancel(parent)
 defer cancel()
 errorsCh:=make(chan error,3)
 go func(){errorsCh<-tunnel.PumpToUDP(ctx,device,conn,peer,secure,mtu,func(packet []byte)error{
  if fullTunnel && (len(packet)==0 || packet[0]>>4!=4){return tunnel.ErrInvalidPacket}
  return tunnel.VerifySource(packet,local)
 })}()
 lastReply:=time.Now()
 replies:=make(chan time.Time,8)
 go func(){
  ticker:=time.NewTicker(5*time.Second)
  defer ticker.Stop()
  for {
   select {
   case <-ctx.Done():return
   case <-ticker.C:
    if time.Since(lastReply)>20*time.Second {errorsCh<-errPeerSilent;return}
    frame,err:=secure.Seal(keepaliveRequest)
    if err==nil{_,err=conn.WriteToUDP(frame,peer)}
    if err!=nil{errorsCh<-err;return}
   case t:=<-replies:lastReply=t
   }
  }
 }()
 go func(){
  buf:=make([]byte,mtu+128)
  for {
   if ctx.Err()!=nil{return}
   if err:=conn.SetReadDeadline(time.Now().Add(250*time.Millisecond));err!=nil{errorsCh<-err;return}
   n,source,err:=conn.ReadFromUDP(buf)
   if ne,ok:=err.(net.Error);ok&&ne.Timeout(){continue}
   if err!=nil{errorsCh<-err;return}
   if !source.IP.Equal(peer.IP)||source.Port!=peer.Port{continue}
   plain,err:=secure.Open(buf[:n])
   if err!=nil{continue}
   if bytes.Equal(plain,keepaliveReply){
    select{case replies<-time.Now():default:}
    continue
   }
   if len(plain)>mtu||!tunnel.IsIP(plain){continue}
   if fullTunnel {
    // Internet replies may have any source, but MUST target this client's
    // assigned tunnel IPv4. The outer packet is authenticated by Noise.
    if plain[0]>>4!=4{continue}
    dst,err:=tunnel.DestinationIP(plain)
    if err!=nil||dst!=local{continue}
   }else if tunnel.VerifySource(plain,allowed)!=nil{continue}
   written,err:=device.Write(plain)
   if err!=nil{errorsCh<-err;return}
   if written!=len(plain){errorsCh<-io.ErrShortWrite;return}
  }
 }()
 select{
 case err:=<-errorsCh:
  cancel();_ = device.Close()
  if errors.Is(err,context.Canceled){return nil}
  return err
 case <-ctx.Done():
  _ = device.Close()
  return nil
 }
}
