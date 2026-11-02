//go:build linux
package app

import (
 "bytes"
 "crypto/sha256"
 "context"
 "errors"
 "fmt"
 "log"
 "net"
 "net/netip"
 "sync"
 "time"

 "github.com/WWWorm92/NetherVPN/internal/session"
 "github.com/WWWorm92/NetherVPN/internal/transport"
 "github.com/WWWorm92/NetherVPN/internal/tun"
 "github.com/WWWorm92/NetherVPN/internal/tunnel"
 "github.com/flynn/noise"
)

type activePeer struct {
 addr *net.UDPAddr
 secure *session.Session
}
// serveContinuous owns the UDP reader throughout its lifetime. No second
// goroutine may ReadFromUDP on this socket.
func serveContinuous(ctx context.Context,c Config,identity noise.DHKey,authorized []byte,allowed netip.Addr) error {
 listen,err:=net.ResolveUDPAddr("udp",c.Listen)
 if err!=nil{return err}
 conn,err:=net.ListenUDP("udp",listen)
 if err!=nil{return err}
 defer conn.Close()
 device,err:=tun.Open(c.Interface)
 if err!=nil{return err}
 defer device.Close()
 if err:=configureInterface(device,c.LocalAddress,c.MTU);err!=nil{return err}
 var mu sync.Mutex
 var current *activePeer
 // Duplicate INIT retransmissions must receive the original Noise reply.
 // Bounded response cache is intentionally short-lived and single-peer.
 type cachedInit struct { digest [32]byte; response []byte; addr string; expires time.Time }
 var cache *cachedInit
 outgoingErr:=make(chan error,1)
 go func(){
  buf:=make([]byte,c.MTU+1)
  for {
   n,e:=device.Read(buf)
   if e!=nil {outgoingErr<-e;return}
   if n>c.MTU||!tunnel.IsIP(buf[:n]) {continue}
   mu.Lock()
   p:=current
   if p!=nil {
    payload,e:=p.secure.Seal(buf[:n])
    if e==nil {_,e=conn.WriteToUDP(payload,p.addr)}
    if e!=nil {log.Printf("server: send error: %v",e)}
   }
   mu.Unlock()
  }
 }()
 buffer:=make([]byte,c.MTU+512)
 if len(buffer)<4096{buffer=make([]byte,4096)}
 for {
  if ctx.Err()!=nil{return nil}
  select {case e:=<-outgoingErr:return fmt.Errorf("tun reader: %w",e);default:}
  if e:=conn.SetReadDeadline(time.Now().Add(250*time.Millisecond));e!=nil{return e}
  n,addr,e:=conn.ReadFromUDP(buffer)
  if ne,ok:=e.(net.Error);ok&&ne.Timeout(){continue}
  if e!=nil {if ctx.Err()!=nil{return nil};return e}
  data:=buffer[:n]
  if n>0&&data[0]==0xA1 {
   digest:=sha256.Sum256(data)
   if cache!=nil&&time.Now().Before(cache.expires)&&cache.digest==digest&&cache.addr==addr.String() {
    if _,e=conn.WriteToUDP(cache.response,addr);e!=nil{log.Printf("handshake: retry response failed: %v",e)}
    continue
   }
   secure,response,e:=transport.HandleInitiation(data,identity,func(candidate []byte)bool{return bytes.Equal(candidate,authorized)})
   if e!=nil {log.Printf("handshake: rejected from %s: %v",addr,e);continue}
   if _,e=conn.WriteToUDP(response,addr);e!=nil {log.Printf("handshake: reply failed: %v",e);continue}
   mu.Lock()
   current=&activePeer{addr:addr,secure:secure}
   cache=&cachedInit{digest:digest,response:append([]byte(nil),response...),addr:addr.String(),expires:time.Now().Add(10*time.Second)}
   mu.Unlock()
   log.Printf("handshake: authenticated peer %s; session replaced",addr)
   continue
  }
  mu.Lock()
  p:=current
  if p==nil||!p.addr.IP.Equal(addr.IP)||p.addr.Port!=addr.Port {mu.Unlock();continue}
  plain,e:=tunnel.DecodeIP(p.secure,data,c.MTU)
  mu.Unlock()
  if e!=nil||tunnel.VerifySource(plain,allowed)!=nil{continue}
  if _,e=device.Write(plain);e!=nil {if errors.Is(e,context.Canceled){return nil};return e}
 }
}
