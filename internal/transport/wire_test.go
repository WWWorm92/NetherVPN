package transport

import (
	"bytes"
	"context"
	"net"
	"testing"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
)

func TestWireHandshakeAndEncryptedUDP(t *testing.T) {
	serverKey, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	clientKey, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	local := &net.UDPAddr{IP: net.IPv4(127,0,0,1)}
	serverConn, err := net.ListenUDP("udp4", local)
	if err != nil { t.Fatal(err) }
	defer serverConn.Close()
	clientConn, err := net.ListenUDP("udp4", local)
	if err != nil { t.Fatal(err) }
	defer clientConn.Close()
	_ = serverConn.SetDeadline(time.Now().Add(5*time.Second))
	done := make(chan error,1)
	go func() {
		secure, _, err := AcceptHandshake(serverConn, serverKey, func(pub []byte) bool { return bytes.Equal(pub, clientKey.Public) })
		if err == nil { err = ServeOne(serverConn, secure, func(payload []byte) []byte { return append([]byte("ack:"), payload...) }) }
		done <- err
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	secure, err := DialHandshake(ctx, clientConn, serverConn.LocalAddr().(*net.UDPAddr), clientKey, serverKey.Public)
	if err != nil { t.Fatal(err) }
	result, err := Exchange(ctx, clientConn, serverConn.LocalAddr().(*net.UDPAddr), secure, []byte("hello"))
	if err != nil { t.Fatal(err) }
	if string(result) != "ack:hello" { t.Fatalf("unexpected reply %q",result) }
	if err := <-done; err != nil { t.Fatal(err) }
}
