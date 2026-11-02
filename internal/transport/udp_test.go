package transport

import (
	"bytes"
	"context"
	"net"
	"testing"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/WWWorm92/NetherVPN/internal/session"
)

func testSessions(t *testing.T) (*session.Session, *session.Session) {
	t.Helper()
	server, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	client, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	i, err := handshake.NewInitiator(client, server.Public)
	if err != nil { t.Fatal(err) }
	r, err := handshake.NewResponder(server)
	if err != nil { t.Fatal(err) }
	first, err := i.Start()
	if err != nil { t.Fatal(err) }
	second, so, si, err := r.Respond(first, func(pub []byte) bool { return bytes.Equal(pub, client.Public) })
	if err != nil { t.Fatal(err) }
	co, ci, err := i.Finish(second)
	if err != nil { t.Fatal(err) }
	c, err := session.New(co, ci, 99, 1)
	if err != nil { t.Fatal(err) }
	s, err := session.New(so, si, 99, 1)
	if err != nil { t.Fatal(err) }
	return c, s
}

func TestEncryptedUDPExchangeLoopback(t *testing.T) {
	c, s := testSessions(t)
	addr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}
	server, err := net.ListenUDP("udp4", addr)
	if err != nil { t.Fatal(err) }
	defer server.Close()
	client, err := net.ListenUDP("udp4", addr)
	if err != nil { t.Fatal(err) }
	defer client.Close()
	server.SetReadDeadline(time.Now().Add(3*time.Second))
	done := make(chan error, 1)
	go func() { done <- ServeOne(server, s, func(b []byte) []byte { return append([]byte("echo:"), b...) }) }()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	got, err := Exchange(ctx, client, server.LocalAddr().(*net.UDPAddr), c, []byte("nether"))
	if err != nil { t.Fatal(err) }
	if string(got) != "echo:nether" { t.Fatalf("unexpected response: %q", got) }
	if err := <-done; err != nil { t.Fatal(err) }
}
