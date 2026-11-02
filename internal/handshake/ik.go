// Package handshake implements the two-message Noise IK handshake.
// It does not implement UDP framing, enrollment, replay caches or tunneling.
package handshake

import (
	"bytes"
	"crypto/rand"
	"errors"

	"github.com/flynn/noise"
)

const Prologue = "NetherVPN/noise-ik/v1"
const keySize = 32

var (
	ErrInvalidKey = errors.New("handshake: invalid public or private key")
	ErrUnauthorized = errors.New("handshake: client is not authorized")
	ErrBadState = errors.New("handshake: invalid handshake state")
)

var suite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2s)

// GenerateIdentity creates a static X25519 keypair. The private key must
// remain only on the peer's device and must never be committed to Git.
func GenerateIdentity() (noise.DHKey, error) {
	return noise.DH25519.GenerateKeypair(rand.Reader)
}

func validateIdentity(key noise.DHKey) error {
	if len(key.Private) != keySize || len(key.Public) != keySize {
		return ErrInvalidKey
	}
	return nil
}

// Initiator knows and pins the server's static public key before connecting.
type Initiator struct {
	state *noise.HandshakeState
	started bool
}

// Responder checks the client's static public key against a locally
// provisioned allowlist before accepting a session.
type Responder struct {
	state *noise.HandshakeState
	processed bool
}

func NewInitiator(local noise.DHKey, pinnedServerPublic []byte) (*Initiator, error) {
	if err := validateIdentity(local); err != nil {
		return nil, err
	}
	if len(pinnedServerPublic) != keySize {
		return nil, ErrInvalidKey
	}
	state, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite,
		Pattern: noise.HandshakeIK,
		Initiator: true,
		Prologue: []byte(Prologue),
		StaticKeypair: local,
		PeerStatic: append([]byte(nil), pinnedServerPublic...),
	})
	if err != nil {
		return nil, err
	}
	return &Initiator{state: state}, nil
}

func NewResponder(local noise.DHKey) (*Responder, error) {
	if err := validateIdentity(local); err != nil {
		return nil, err
	}
	state, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite,
		Pattern: noise.HandshakeIK,
		Initiator: false,
		Prologue: []byte(Prologue),
		StaticKeypair: local,
	})
	if err != nil {
		return nil, err
	}
	return &Responder{state: state}, nil
}

// Start generates the initial authenticated Noise IK handshake message.
func (i *Initiator) Start() ([]byte, error) {
	if i.started {
		return nil, ErrBadState
	}
	i.started = true
	msg, _, _, err := i.state.WriteMessage(nil, nil)
	return msg, err
}

// Respond verifies message 1 and authorizes the extracted client identity.
// The caller MUST provide a non-nil authorization function that checks a
// trusted local allowlist. Returned directional CipherStates are for
// ordered Noise transport messages; they are NOT yet wired to UDP DATA.
func (r *Responder) Respond(message []byte, authorize func([]byte) bool) ([]byte, *noise.CipherState, *noise.CipherState, error) {
	if r.processed {
		return nil, nil, nil, ErrBadState
	}
	r.processed = true
	if authorize == nil {
		return nil, nil, nil, ErrUnauthorized
	}
	if _, _, _, err := r.state.ReadMessage(nil, message); err != nil {
		return nil, nil, nil, err
	}
	clientPublic := r.state.PeerStatic()
	if len(clientPublic) != keySize || !authorize(bytes.Clone(clientPublic)) {
		return nil, nil, nil, ErrUnauthorized
	}
	message2, initiatorToResponder, responderToInitiator, err := r.state.WriteMessage(nil, nil)
	return message2, responderToInitiator, initiatorToResponder, err
}

// Finish authenticates the server response. Returned directional cipher
// states apply to ordered transport use ONLY.
func (i *Initiator) Finish(response []byte) (*noise.CipherState, *noise.CipherState, error) {
	if !i.started {
		return nil, nil, ErrBadState
	}
	_, outgoing, incoming, err := i.state.ReadMessage(nil, response)
	return outgoing, incoming, err
}
