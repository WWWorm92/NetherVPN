package transport

import (
	"encoding/binary"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/WWWorm92/NetherVPN/internal/session"
	"github.com/flynn/noise"
)

func TestMultiClientIsolationAndHandshakeReplay(t *testing.T) {
	serverKey, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	clientA, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	clientB, err := handshake.GenerateIdentity()
	if err != nil { t.Fatal(err) }
	allowed := map[string]bool{string(clientA.Public): true, string(clientB.Public): true}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP:net.IPv4(127,0,0,1)})
	if err != nil { t.Fatal(err) }
	defer conn.Close()
	server, err := NewServer(conn, serverKey, func(pub []byte) bool {
		return allowed[string(pub)]
	}, 2, time.Minute)
	if err != nil { t.Fatal(err) }
	addrs := []*net.UDPAddr{
		{IP:net.IPv4(127,0,0,1), Port:10001},
		{IP:net.IPv4(127,0,0,1), Port:10002},
	}
	keys := []noise.DHKey{clientA, clientB}
	for idx, local := range keys {
		init, err := handshake.NewInitiator(local, serverKey.Public)
		if err != nil { t.Fatal(err) }
		first, err := init.Start()
		if err != nil { t.Fatal(err) }
		frame := append([]byte{wireInit}, first...)
		reply, err := server.ServePacket(frame, addrs[idx], func(b []byte) []byte {return b})
		if err != nil { t.Fatal(err) }
		parameters, outgoing, incoming, err := init.FinishWithPayload(reply[1:])
		if err != nil || len(parameters) != 10 { t.Fatalf("finish: %v", err) }
		id := binary.BigEndian.Uint64(parameters[:8])
		epoch := binary.BigEndian.Uint16(parameters[8:])
		clientSession, err := session.New(outgoing, incoming, id, epoch)
		if err != nil { t.Fatal(err) }
		data, err := clientSession.Seal([]byte{byte(idx)})
		if err != nil { t.Fatal(err) }
		other := addrs[1-idx]
		if _, err := server.ServePacket(data, other, func(b []byte) []byte{return b}); !errors.Is(err, ErrUnknownSession) {
			t.Fatalf("wrong source accepted: %v", err)
		}
		encryptedResponse, err := server.ServePacket(data, addrs[idx], func(b []byte) []byte {return append([]byte("echo"), b...)})
		if err != nil { t.Fatal(err) }
		decrypted, err := clientSession.Open(encryptedResponse)
		if err != nil || len(decrypted) != 5 || decrypted[4] != byte(idx) {
			t.Fatalf("session %d response invalid: %x, %v", idx, decrypted, err)
		}
		if _, err := server.ServePacket(frame, addrs[idx], func(b []byte) []byte{return b}); !errors.Is(err, ErrReplayHandshake) {
			t.Fatalf("replay: %v", err)
		}
	}
	if len(server.sessions) != 2 { t.Fatalf("sessions=%d",len(server.sessions)) }
}
