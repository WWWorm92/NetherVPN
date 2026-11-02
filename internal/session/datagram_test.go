package session

import (
	"bytes"
	"errors"
	"testing"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/flynn/noise"
)

func connected(t *testing.T) (*Session, *Session) {
	t.Helper()
	server, err := handshake.GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	client, err := handshake.GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	i, err := handshake.NewInitiator(client, server.Public)
	if err != nil {
		t.Fatal(err)
	}
	r, err := handshake.NewResponder(server)
	if err != nil {
		t.Fatal(err)
	}
	first, err := i.Start()
	if err != nil {
		t.Fatal(err)
	}
	second, serverOut, serverIn, err := r.Respond(first, func(pub []byte) bool {
		return bytes.Equal(pub, client.Public)
	})
	if err != nil {
		t.Fatal(err)
	}
	clientOut, clientIn, err := i.Finish(second)
	if err != nil {
		t.Fatal(err)
	}
	c, err := New(clientOut, clientIn, 1048577, 1)
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(serverOut, serverIn, 1048577, 1)
	if err != nil {
		t.Fatal(err)
	}
	return c, s
}

func TestDatagramsOutOfOrderAndBothDirections(t *testing.T) {
	client, server := connected(t)
	first, err := client.Seal([]byte("first"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := client.Seal([]byte("second"))
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		packet []byte
		want string
	}{{second, "second"}, {first, "first"}} {
		plain, err := server.Open(tc.packet)
		if err != nil || string(plain) != tc.want {
			t.Fatalf("got %q, %v; want %q", plain, err, tc.want)
		}
	}
	if _, err := server.Open(first); !errors.Is(err, ErrReplay) {
		t.Fatalf("duplicate not rejected: %v", err)
	}
	response, err := server.Seal([]byte("reply"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := client.Open(response)
	if err != nil || string(plain) != "reply" {
		t.Fatalf("reverse direction: %q %v", plain, err)
	}
}

func TestTamperFailsWithoutConsumingCounter(t *testing.T) {
	client, server := connected(t)
	packet, err := client.Seal([]byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	tampered := bytes.Clone(packet)
	tampered[19] ^= 1
	if _, err := server.Open(tampered); !errors.Is(err, ErrAuth) {
		t.Fatalf("tampered counter accepted: %v", err)
	}
	tampered = bytes.Clone(packet)
	tampered[len(tampered)-1] ^= 1
	if _, err := server.Open(tampered); !errors.Is(err, ErrAuth) {
		t.Fatalf("tampered tag accepted: %v", err)
	}
	if _, err := server.Open(packet); err != nil {
		t.Fatalf("valid packet failed after tampering: %v", err)
	}
}

func TestRejectMissingCipherStates(t *testing.T) {
	if _, err := New(nil, nil, 1, 1); !errors.Is(err, ErrSession) {
		t.Fatalf("nil cipher accepted: %v", err)
	}
	// A single CipherState may never serve both directions.
	var state *noise.CipherState
	if _, err := New(state, state, 1, 1); !errors.Is(err, ErrSession) {
		t.Fatalf("same cipher accepted: %v", err)
	}
}
