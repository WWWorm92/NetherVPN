// Package session binds Noise IK transport ciphers to NetherVPN datagram framing.
// The caller must complete an authenticated handshake before creating a Session.
package session

import (
	"errors"
	"math"
	"sync"

	"github.com/WWWorm92/NetherVPN/internal/protocol"
	"github.com/flynn/noise"
)

var (
	ErrSession = errors.New("session: invalid cipher state or connection ID")
	ErrPeer = errors.New("session: connection ID or epoch mismatch")
	ErrAuth = errors.New("session: authentication failed")
	ErrReplay = errors.New("session: replayed or expired datagram")
	ErrRekey = errors.New("session: new handshake required")
)

// Session is bound to one Noise handshake, one connection ID and epoch.
// Do not persist or reuse it after a restart; create a fresh handshake.
// Neither handshake retransmission nor rekey is implemented yet.
type Session struct {
	send noise.Cipher
	recv noise.Cipher
	connectionID uint64
	epoch uint16
	sendMu sync.Mutex
	next uint64
	recvMu sync.Mutex
	window protocol.ReplayWindow
}

// New consumes two directional Noise cipher states obtained from a completed
// authenticated handshake. Cipher() invalidates each ordered state, ensuring
// no caller can accidentally reuse its automatic nonce sequence.
func New(send, recv *noise.CipherState, connectionID uint64, epoch uint16) (*Session, error) {
	if send == nil || recv == nil || send == recv || connectionID == 0 {
		return nil, ErrSession
	}
	return &Session{send: send.Cipher(), recv: recv.Cipher(), connectionID: connectionID, epoch: epoch}, nil
}

// Seal assigns a counter once and uses it as the Noise ChaChaPoly nonce.
// The fixed protocol header is authenticated as associated data.
func (s *Session) Seal(plain []byte) ([]byte, error) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	// Reserve the Noise-rekey nonce and require a new handshake well before it.
	if s.next >= noise.MaxNonce {
		return nil, ErrRekey
	}
	seq := s.next
	s.next++
	header := protocol.Header{ConnectionID: s.connectionID, KeyEpoch: s.epoch, Counter: seq}.MarshalBinary()
	return s.send.Encrypt(header, seq, header, plain), nil
}

// Open verifies authenticity before advancing the replay window.
// The receive cipher supports UDP reordering via explicit sequence nonces.
func (s *Session) Open(packet []byte) ([]byte, error) {
	h, err := protocol.ParseHeader(packet)
	if err != nil {
		return nil, err
	}
	if h.ConnectionID != s.connectionID || h.KeyEpoch != s.epoch || h.Counter >= math.MaxUint64-1 {
		return nil, ErrPeer
	}
	s.recvMu.Lock()
	defer s.recvMu.Unlock()
	plain, err := s.recv.Decrypt(nil, h.Counter, packet[:protocol.HeaderSize], packet[protocol.HeaderSize:])
	if err != nil {
		return nil, ErrAuth
	}
	if !s.window.Accept(h.Counter) {
		return nil, ErrReplay
	}
	return plain, nil
}
