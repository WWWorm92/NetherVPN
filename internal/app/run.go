package app

import (
		"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
 "os/exec"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/transport"
 "github.com/WWWorm92/NetherVPN/internal/routing"
 "github.com/WWWorm92/NetherVPN/internal/peers"
	"github.com/WWWorm92/NetherVPN/internal/tun"
	"github.com/flynn/noise"
)

type Config struct {
	Interface string
	Listen string
	Peer string
	PrivateKey string
	PeerPublicKey string
	AllowedSource string
	LocalAddress string
	MTU int
 PeersDir string
 FullTunnel bool
 DNS string
}

func (c Config) validate() (noise.DHKey,[]byte,netip.Addr,error) {
	if !tun.ValidName(c.Interface) || c.MTU<40 || c.MTU>9000 {
		return noise.DHKey{},nil,netip.Addr{},errors.New("app: invalid interface or MTU")
	}
	identity,err:=ParseIdentity(c.PrivateKey)
	if err!=nil{return noise.DHKey{},nil,netip.Addr{},err}
	pub,err:=ParsePublic(c.PeerPublicKey)
	if err!=nil{return noise.DHKey{},nil,netip.Addr{},err}
	addr,err:=netip.ParseAddr(c.AllowedSource)
	if err!=nil || addr.IsUnspecified(){return noise.DHKey{},nil,netip.Addr{},errors.New("app: invalid allowed source")}
	return identity,pub,addr,nil
}

// runClientOnce opens a new TUN after Noise authentication. In full-tunnel
// mode the fail-closed policy stays installed during reconnect attempts.
func runClientOnce(ctx context.Context,c Config,route *routing.Controller) error {
	identity,pub,source,err:=c.validate()
	if err!=nil{return err}
	peer,err:=net.ResolveUDPAddr("udp",c.Peer)
	if err!=nil{return err}
	conn,err:=net.ListenUDP("udp",nil)
	if err!=nil{return err}
	defer conn.Close()
	handshakeCtx,cancel:=context.WithTimeout(ctx,8*time.Second)
	secure,err:=transport.DialHandshake(handshakeCtx,conn,peer,identity,pub)
	cancel()
	if err!=nil{return fmt.Errorf("handshake: %w",err)}
	fmt.Println("client: authenticated handshake complete")
	device,err:=tun.Open(c.Interface)
	if err!=nil{return err}
	defer device.Close()
	if err:=configureInterface(device,c.LocalAddress,c.MTU);err!=nil{return err}
 if c.FullTunnel {
  if route==nil{return errors.New("client: missing full-tunnel controller")}
  if err:=route.Up();err!=nil{return fmt.Errorf("client: DNS/routing setup: %w",err)}
  defer route.Down()
 } else if source.Is4() {
  // Generated profiles use /32 addresses: without an explicit peer route,
  // even split-tunnel mode cannot send packets to the server's TUN address.
  if output,e:=exec.Command("ip","-4","route","replace",source.String()+"/32","dev",device.Name()).CombinedOutput();e!=nil{
   return fmt.Errorf("client: split-tunnel peer route: %w: %s",e,output)
  }
 }
 localPrefix,e:=netip.ParsePrefix(c.LocalAddress)
 if e!=nil{return fmt.Errorf("client: invalid local address: %w",e)}
 fmt.Println("client: encrypted tunnel active")
	return runPumps(ctx,device,conn,peer,secure,c.MTU,source,localPrefix.Addr(),c.FullTunnel)
}

func RunServer(ctx context.Context,c Config) error {
 identity,pub,source,err:=c.validate()
 if err!=nil{return err}
 all:=make(map[[32]byte]netip.Addr)
 var legacy [32]byte
 copy(legacy[:],pub)
 all[legacy]=source
 used:=map[netip.Addr]bool{source:true}
 subnet,err:=netip.ParsePrefix(c.LocalAddress)
 if err!=nil||!subnet.Addr().Is4(){return errors.New("server: IPv4 Interface.Address CIDR is required")}
 for _,entry:=range func() []peers.Peer {
  registered,e:=peers.Load(c.PeersDir)
  if e!=nil{err=e}
  return registered
 }(){
  if err!=nil{break}
  if !subnet.Contains(entry.IP)||used[entry.IP] {
   return fmt.Errorf("server: duplicate or out-of-subnet client IP: %s",entry.IP)
  }
  if _,exists:=all[entry.PublicKey];exists{
   return fmt.Errorf("server: duplicate public key for %s",entry.Name)
  }
  all[entry.PublicKey]=entry.IP
  used[entry.IP]=true
 }
 if err!=nil{return err}
 return serveContinuous(ctx,c,identity,all)
}

func RunClient(ctx context.Context,c Config) error {
 var controller *routing.Controller
 if c.FullTunnel {
  host,_,err:=net.SplitHostPort(c.Peer)
  if err!=nil{return fmt.Errorf("client: invalid full-tunnel endpoint: %w",err)}
  controller,err=routing.New(c.Interface,host,c.DNS)
  if err!=nil{return err}
  if err=controller.Prepare();err!=nil{return err}
  defer controller.Close()
  fmt.Println("client: full IPv4 tunnel enabled; server endpoint bypass active; IPv6 fail-closed")
 }
 delay:=time.Second
 for {
  if ctx.Err()!=nil{return nil}
  err:=runClientOnce(ctx,c,controller)
  if ctx.Err()!=nil{return nil}
  if err==nil{return nil}
  fmt.Printf("client: %v; retrying in %s\n",err,delay)
  timer:=time.NewTimer(delay)
  select{case <-ctx.Done():timer.Stop();return nil;case <-timer.C:}
  if delay<8*time.Second{delay*=2}
 }
}
