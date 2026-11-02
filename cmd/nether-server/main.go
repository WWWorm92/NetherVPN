package main
import(
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
 config:=flag.String("config","","NetherVPN server profile (.conf)")
 flag.StringVar(&c.Interface,"tun","nvpn0","TUN name")
 flag.StringVar(&c.Listen,"listen","0.0.0.0:51830","UDP listen address")
 flag.StringVar(&c.PrivateKey,"private-key","","deprecated: private key on argv (insecure)")
 flag.StringVar(&c.PeerPublicKey,"client-key","","authorized client public key")
 flag.StringVar(&c.AllowedSource,"client-ip","","remote tunnel IP")
 flag.IntVar(&c.MTU,"mtu",1280,"TUN MTU")
 flag.Parse()
 if *config!=""{
  p,err:=profile.Load(*config);if err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
  port:=p.ListenPort;if port==""{port="51830"}
  c=app.Config{Interface:p.Interface,Listen:"0.0.0.0:"+port,PrivateKey:p.PrivateKey,PeerPublicKey:p.PublicKey,AllowedSource:p.AllowedIP,LocalAddress:p.Address,MTU:p.MTU}
 }
 ctx,stop:=signal.NotifyContext(context.Background(),os.Interrupt,syscall.SIGTERM);defer stop()
 if err:=app.RunServer(ctx,c);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
}
