package main
import (
 "context"
 "flag"
 "fmt"
 "os"
 "os/signal"
 "syscall"
 "github.com/WWWorm92/NetherVPN/internal/app"
 "github.com/WWWorm92/NetherVPN/internal/clientsettings"
 "github.com/WWWorm92/NetherVPN/internal/profile"
)
func main(){
 var c app.Config
 config:=flag.String("config","","NetherVPN identity profile (.conf)")
 settingsPath:=flag.String("settings","","local routing settings, independent of .conf")
 flag.StringVar(&c.Interface,"tun","nvpn0","TUN name")
 flag.StringVar(&c.Peer,"peer","","server UDP address")
 flag.StringVar(&c.PrivateKey,"private-key","","deprecated: private key on argv (insecure)")
 flag.StringVar(&c.PeerPublicKey,"server-key","","pinned server public key")
 flag.StringVar(&c.AllowedSource,"server-ip","","remote tunnel IP")
 flag.IntVar(&c.MTU,"mtu",1280,"TUN MTU")
 flag.BoolVar(&c.FullTunnel,"full-tunnel",false,"route all IPv4 and DNS via VPN (requires a validated config)")
 flag.StringVar(&c.DNS,"dns","1.1.1.1","full tunnel IPv4 DNS resolver")
 flag.Parse()
 if *config!=""{
  p,err:=profile.Load(*config);if err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
  if p.Endpoint==""{fmt.Fprintln(os.Stderr,"profile: missing Peer.Endpoint");os.Exit(1)}
  c=app.Config{Interface:p.Interface,Peer:p.Endpoint,PrivateKey:p.PrivateKey,PeerPublicKey:p.PublicKey,AllowedSource:p.AllowedIP,LocalAddress:p.Address,MTU:p.MTU,FullTunnel:p.FullTunnel,DNS:p.DNS}
 }
 if c.FullTunnel && *config==""{fmt.Fprintln(os.Stderr,"full tunnel requires a file profile, not keys on command line");os.Exit(1)}
 if *settingsPath!=""{
  settings,exists,err:=clientsettings.LoadIfExists(*settingsPath)
  if err!=nil{fmt.Fprintln(os.Stderr,"settings:",err);os.Exit(1)}
  if exists {c.FullTunnel=settings.FullTunnel;c.DNS=settings.DNS}
 }
 ctx,stop:=signal.NotifyContext(context.Background(),os.Interrupt,syscall.SIGTERM);defer stop()
 if err:=app.RunClient(ctx,c);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
}
