package handshake

import (
	"bytes"
	"errors"
	"testing"
)

func identity(t *testing.T) []byte {
	t.Helper()
	key, err := GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	return key.Public
}

func TestIKAuthenticationAndDirectionKeys(t *testing.T) {
	server, err := GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	client, err := GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	initiator, err := NewInitiator(client, server.Public)
	if err != nil {
		t.Fatal(err)
	}
	responder, err := NewResponder(server)
	if err != nil {
		t.Fatal(err)
	}
	request, err := initiator.Start()
	if err != nil {
		t.Fatal(err)
	}
	response, serverSend, serverRecv, err := responder.Respond(request, func(pub []byte) bool {
		return bytes.Equal(pub, client.Public)
	})
	if err != nil {
		t.Fatal(err)
	}
	clientSend, clientRecv, err := initiator.Finish(response)
	if err != nil {
		t.Fatal(err)
	}
	msg, err := clientSend.Encrypt(nil, []byte("aad"), []byte("from client"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := serverRecv.Decrypt(nil, []byte("aad"), msg)
	if err != nil || string(plain) != "from client" {
		t.Fatalf("client -> server: %q %v", plain, err)
	}
	msg, err = serverSend.Encrypt(nil, []byte("aad"), []byte("from server"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err = clientRecv.Decrypt(nil, []byte("aad"), msg)
	if err != nil || string(plain) != "from server" {
		t.Fatalf("server -> client: %q %v", plain, err)
	}
}

func TestIKUnauthorizedAndWrongServer(t *testing.T) {
	server, _ := GenerateIdentity()
	client, _ := GenerateIdentity()
	otherServer, _ := GenerateIdentity()
	initiator, _ := NewInitiator(client, server.Public)
	responder, _ := NewResponder(server)
	request, err := initiator.Start()
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := responder.Respond(request, func([]byte) bool { return false }); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("unauthorized client accepted: %v", err)
	}
	initiator2, _ := NewInitiator(client, otherServer.Public)
	responder2, _ := NewResponder(server)
	wrongRequest, _ := initiator2.Start()
	if _, _, _, err := responder2.Respond(wrongRequest, func([]byte) bool { return true }); err == nil {
		t.Fatal("wrong server public key accepted")
	}
}

func TestIKMissingAuthorization(t *testing.T) {
	server, _ := GenerateIdentity()
	client, _ := GenerateIdentity()
	initiator, _ := NewInitiator(client, server.Public)
	responder, _ := NewResponder(server)
	request, _ := initiator.Start()
	if _, _, _, err := responder.Respond(request, nil); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("nil authorizer accepted: %v", err)
	}
}

func TestBadKeys(t *testing.T) {
	server, _ := GenerateIdentity()
	if _, err := NewInitiator(server, []byte{1}); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("accepted bad server public key: %v", err)
	}
	_ = identity(t)
}
