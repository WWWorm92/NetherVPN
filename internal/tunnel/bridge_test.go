package tunnel

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/WWWorm92/NetherVPN/internal/session"
)

func pair(t *testing.T)(*session.Session,*session.Session) {
	t.Helper()
	server,err:=handshake.GenerateIdentity()
	if err!=nil { t.Fatal(err) }
	client,err:=handshake.GenerateIdentity()
	if err!=nil { t.Fatal(err) }
	i,err:=handshake.NewInitiator(client,server.Public)
	if err!=nil { t.Fatal(err) }
	r,err:=handshake.NewResponder(server)
	if err!=nil { t.Fatal(err) }
	first,err:=i.Start()
	if err!=nil { t.Fatal(err) }
	second,serverOut,serverIn,err:=r.Respond(first,func(pub []byte)bool{return bytes.Equal(pub,client.Public)})
	if err!=nil { t.Fatal(err) }
	clientOut,clientIn,err:=i.Finish(second)
	if err!=nil { t.Fatal(err) }
	a,err:=session.New(clientOut,clientIn,9,1)
	if err!=nil { t.Fatal(err) }
	b,err:=session.New(serverOut,serverIn,9,1)
	if err!=nil { t.Fatal(err) }
	return a,b
}

func ipv4() []byte {
	p:=make([]byte,24)
	p[0]=0x45
	binary.BigEndian.PutUint16(p[2:4],uint16(len(p)))
	p[8]=64
	p[9]=1
	copy(p[12:16],[]byte{10,77,0,2})
	copy(p[16:20],[]byte{10,77,0,1})
	copy(p[20:],[]byte{1,2,3,4})
	return p
}

func TestEncryptedIPBridge(t *testing.T){
	client,server:=pair(t)
	ip:=ipv4()
	wire,err:=EncodeIP(client,ip,DefaultMTU)
	if err!=nil { t.Fatal(err) }
	got,err:=DecodeIP(server,wire,DefaultMTU)
	if err!=nil || !bytes.Equal(ip,got){t.Fatalf("roundtrip: %x, %v",got,err)}
	if _,err:=DecodeIP(server,wire,DefaultMTU);err==nil{t.Fatal("replay accepted")}
}

func TestIPValidation(t *testing.T) {
	ipv6 := make([]byte, 44)
	ipv6[0] = 0x60
	binary.BigEndian.PutUint16(ipv6[4:6], 4)
	if !IsIP(ipv6) { t.Fatal("valid ipv6 rejected") }
	ipv6[5] = 5
	if IsIP(ipv6) { t.Fatal("invalid ipv6 length accepted") }

	if !IsIP(ipv4()) { t.Fatal("ipv4 rejected") }
	cases:=[][]byte{nil,{0x45},{0x60},{0x45,0,0,20}}
	for _,packet:=range cases {
		if IsIP(packet){t.Fatalf("malformed IP accepted: %x",packet)}
	}
}

func TestUDPToTunWriter(t *testing.T){
	client,server:=pair(t)
	local:=&net.UDPAddr{IP:net.IPv4(127,0,0,1)}
	sender,err:=net.ListenUDP("udp4",local)
	if err!=nil { t.Fatal(err) }
	defer sender.Close()
	receiver,err:=net.ListenUDP("udp4",local)
	if err!=nil { t.Fatal(err) }
	defer receiver.Close()
	ip:=ipv4()
	if err:=SendOne(bytes.NewReader(ip),sender,receiver.LocalAddr().(*net.UDPAddr),client,DefaultMTU);err!=nil{t.Fatal(err)}
	var written bytes.Buffer
	ctx,cancel:=context.WithTimeout(context.Background(),time.Second*3)
	defer cancel()
	if err:=ReceiveOne(ctx,&written,receiver,sender.LocalAddr().(*net.UDPAddr),server,DefaultMTU);err!=nil{t.Fatal(err)}
	if !bytes.Equal(ip,written.Bytes()){t.Fatal("TUN packet mismatch")}
}
