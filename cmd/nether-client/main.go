package main
import (
 "context"
 "flag"
 "fmt"
 "os"
 "os/signal"
 "syscall"
 "github.com/WWWorm92/NetherVPN/internal/app"
 "github.com/WWWorm92/NetherVPN/internal/profile"
)
func main(){
 var c app.Config
 config:=flag.String("config","","NetherVPN profile (.conf)")
 flag.StringVar(&c.Interface,"tun","nvpn0","TUN name")
 flag.StringVar(&c.Peer,"peer","","server UDP address")
 flag.StringVar(&c.PrivateKey,"private-key","","deprecated: private key on argv (insecure)")
 flag.StringVar(&c.PeerPublicKey,"server-key","","pinned server public key")
 flag.StringVar(&c.AllowedSource,"server-ip","","remote tunnel IP")
 flag.IntVar(&c.MTU,"mtu",1280,"TUN MTU")
 flag.Parse()
 if *config!=""{
  p,err:=profile.Load(*config);if err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
  if p.Endpoint==""{fmt.Fprintln(os.Stderr,"profile: missing Peer.Endpoint");os.Exit(1)}
  c=app.Config{Interface:p.Interface,Peer:p.Endpoint,PrivateKey:p.PrivateKey,PeerPublicKey:p.PublicKey,AllowedSource:p.AllowedIP,LocalAddress:p.Address,MTU:p.MTU}
 }
 ctx,stop:=signal.NotifyContext(context.Background(),os.Interrupt,syscall.SIGTERM);defer stop()
 if err:=app.RunClient(ctx,c);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
}
