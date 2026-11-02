package transport

import (
	"bytes"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
)

func TestMultiClientIsolationAndHandshakeReplay(t *testing.T){
	serverKey,_:=handshake.GenerateIdentity()
	clientA,_:=handshake.GenerateIdentity()
	clientB,_:=handshake.GenerateIdentity()
	allowed:=map[string]bool{string(clientA.Public):true,string(clientB.Public):true}
	conn,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)})
	if err!=nil { t.Fatal(err) }
	defer conn.Close()
	s,err:=NewServer(conn,serverKey,func(pub []byte)bool{return allowed[string(pub)]},2,time.Minute)
	if err!=nil { t.Fatal(err) }
	addrA:=&net.UDPAddr{IP:net.IPv4(127,0,0,1),Port:10001}
	addrB:=&net.UDPAddr{IP:net.IPv4(127,0,0,1),Port:10002}
	clients:=[]struct{key []byte; remote *net.UDPAddr; identity int}{{clientA.Public,addrA,0},{clientB.Public,addrB,1}}
	for _,tc:=range clients {
		var local = clientA
		if tc.identity==1 { local=clientB }
		init,err:=handshake.NewInitiator(local,serverKey.Public)
		if err!=nil { t.Fatal(err) }
		first,err:=init.Start()
		if err!=nil { t.Fatal(err) }
		frame:=append([]byte{wireInit},first...)
		resp,err:=s.ServePacket(frame,tc.remote,func(b []byte)[]byte{return b})
		if err!=nil { t.Fatal(err) }
		params, out, in, err:=init.FinishWithPayload(resp[1:]); if err!=nil || len(params)!=10 || out==nil || in==nil { t.Fatalf("finish: %v",err) }
		if _,err:=s.ServePacket(frame,tc.remote,func(b []byte)[]byte{return b});!errors.Is(err,ErrReplayHandshake){ t.Fatalf("replay: %v",err) }
	}
	if len(s.sessions)!=2 { t.Fatalf("sessions=%d",len(s.sessions)) }
	_ = bytes.Equal
}
