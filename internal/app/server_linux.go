//go:build linux

package app

import (
 "bytes"
 "context"
 "crypto/sha256"
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
 source netip.Addr
 secure *session.Session
 authenticatedPings uint64
 inboundData uint64
 outboundData uint64
}
type cachedInit struct {
 response []byte
 expires time.Time
}

// serveContinuous has a single UDP reader and a single TUN reader. All session
// encryption/decryption, endpoint maps and reply writes are serialized by mu.
// Every client has a distinct static key, assigned tunnel address and session.
func serveContinuous(ctx context.Context,c Config,identity noise.DHKey,authorized map[[32]byte]netip.Addr) error {
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
 byIP:=make(map[netip.Addr]*activePeer)
 byRemote:=make(map[string]*activePeer)
 cache:=make(map[string]cachedInit)
 lastRejectedLog:=time.Time{}
 outgoingErr:=make(chan error,1)

 go func(){
  buffer:=make([]byte,c.MTU+1)
  for {
   n,e:=device.Read(buffer)
   if e!=nil {select{case outgoingErr<-e:default:};return}
   if n>c.MTU || !tunnel.IsIP(buffer[:n]) {continue}
   destination,e:=tunnel.DestinationIP(buffer[:n])
   if e!=nil{continue}
   mu.Lock()
   peer:=byIP[destination]
   if peer!=nil {
    peer.outboundData++
    if peer.outboundData==1 { log.Printf("server: first IPv4 response routed into tunnel for client %s (%d B)",destination,n) }
    payload,e:=peer.secure.Seal(buffer[:n])
    if e==nil{_,e=conn.WriteToUDP(payload,peer.addr)}
    if e!=nil{log.Printf("server: send to %s failed: %v",destination,e)}
   }
   mu.Unlock()
  }
 }()

 buffer:=make([]byte,c.MTU+512)
 if len(buffer)<4096{buffer=make([]byte,4096)}
 for {
  if ctx.Err()!=nil{return nil}
  select {
  case e:=<-outgoingErr:
   if ctx.Err()!=nil{return nil}
   return fmt.Errorf("tun reader: %w",e)
  default:
  }
  if e:=conn.SetReadDeadline(time.Now().Add(250*time.Millisecond));e!=nil{return e}
  n,addr,e:=conn.ReadFromUDP(buffer)
  if ne,ok:=e.(net.Error);ok&&ne.Timeout(){continue}
  if e!=nil {if ctx.Err()!=nil{return nil};return e}
  data:=buffer[:n]
  remote:=addr.String()

  if n>0 && data[0]==0xA1 {
   digest:=sha256.Sum256(data)
   cacheKey:=remote+":"+string(digest[:])
   if existing,ok:=cache[cacheKey];ok&&time.Now().Before(existing.expires){
    if _,e=conn.WriteToUDP(existing.response,addr);e!=nil{log.Printf("server: retry reply: %v",e)}
    continue
   }
   if len(cache)>=128 {
    now:=time.Now()
    for key,entry:=range cache{if !now.Before(entry.expires){delete(cache,key)}}
    // If all 128 entries are still fresh, stop accepting initiations briefly.
    if len(cache)>=128{continue}
   }
   var selected netip.Addr
   secure,response,e:=transport.HandleInitiation(data,identity,func(candidate []byte)bool{
    if len(candidate)!=32{return false}
    var key [32]byte
    copy(key[:],candidate)
    ip,ok:=authorized[key]
    if ok{selected=ip}
    return ok
   })
   if e!=nil {
    // Avoid logging raw packets or keys; bounded warning allows distinguishing
    // untrusted/invalid Noise initiations from UDP reachability failures.
    if time.Since(lastRejectedLog)>5*time.Second {
     log.Printf("server: rejected Noise initiation from %s: %v",addr,e)
     lastRejectedLog=time.Now()
    }
    continue
   }
   if _,e=conn.WriteToUDP(response,addr);e!=nil{log.Printf("server: handshake reply failed: %v",e);continue}
   mu.Lock()
   if previous:=byIP[selected];previous!=nil{
    delete(byRemote,previous.addr.String())
   }
   if previous:=byRemote[remote];previous!=nil{
    delete(byIP,previous.source)
   }
   p:=&activePeer{addr:addr,source:selected,secure:secure}
   byIP[selected]=p
   byRemote[remote]=p
   mu.Unlock()
   cache[cacheKey]=cachedInit{response:bytes.Clone(response),expires:time.Now().Add(10*time.Second)}
   log.Printf("server: authenticated client %s from %s",selected,remote)
   continue
  }

  mu.Lock()
  p:=byRemote[remote]
  if p==nil {mu.Unlock();continue}
  plain,e:=p.secure.Open(data)
  if e==nil && bytes.Equal(plain,[]byte("\x00NVPN-PING-v1")){
   p.authenticatedPings++
   if p.authenticatedPings == 1 { log.Printf("server: first authenticated encrypted PING from client %s", p.source) }
   reply,sealErr:=p.secure.Seal([]byte("\x00NVPN-PONG-v1"))
   if sealErr==nil {_,sealErr=conn.WriteToUDP(reply,addr)}
   if sealErr!=nil{log.Printf("server: keepalive failed: %v",sealErr)}
   mu.Unlock()
   continue
  }
  if e!=nil||len(plain)>c.MTU||!tunnel.IsIP(plain)||tunnel.VerifySource(plain,p.source)!=nil{
   mu.Unlock()
   continue
  }
  p.inboundData++
  if p.inboundData==1 { log.Printf("server: first decrypted IPv4 packet from client %s accepted (%d B)",p.source,len(plain)) }
  mu.Unlock()
  if _,e=device.Write(plain);e!=nil{
   if errors.Is(e,context.Canceled)||ctx.Err()!=nil{return nil}
   return e
  }
 }
}
