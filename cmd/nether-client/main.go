package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/WWWorm92/NetherVPN/internal/app"
)

func main() {
	var c app.Config
	flag.StringVar(&c.Interface,"tun","nvpn0","preconfigured Linux TUN name")
	flag.StringVar(&c.Peer,"peer","127.0.0.1:51830","server UDP address")
	flag.StringVar(&c.PrivateKey,"private-key","","hex-encoded client private key (lab only; command line exposes secrets)")
	flag.StringVar(&c.PeerPublicKey,"server-key","","hex-encoded pinned server public key")
	flag.StringVar(&c.AllowedSource,"server-ip","10.77.0.1","authorized server tunnel IP")
	flag.IntVar(&c.MTU,"mtu",1280,"TUN MTU")
	flag.Parse()
	ctx,stop:=signal.NotifyContext(context.Background(),os.Interrupt,syscall.SIGTERM)
	defer stop()
	if err:=app.RunClient(ctx,c);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
}
