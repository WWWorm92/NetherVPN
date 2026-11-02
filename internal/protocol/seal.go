package protocol

import (
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"math"
	"sync"
)

var (
	ErrAuthentication = errors.New("protocol: authentication failed")
	ErrReplay = errors.New("protocol: replayed or expired packet")
	ErrCounterExhausted = errors.New("protocol: packet counter exhausted")
	ErrBadAEAD = errors.New("protocol: expected a 12-byte nonce and 16-byte tag AEAD")
)

// SessionCodec is the encrypted data-packet codec for one direction of a
// session and one key epoch. The caller must supply a UNIQUE send key for each
// direction and epoch. The codec never performs key agreement or rekeying.
// Concurrent calls to Seal or Open are serialized on their respective locks.
type SessionCodec struct {
	aead cipher.AEAD
	connectionID uint64
	epoch uint16

	sendMu sync.Mutex
	nextCounter uint64
	exhausted bool

	recvMu sync.Mutex
	replay ReplayWindow
}

// NewSessionCodec requires an AEAD with 96-bit nonces and 128-bit tags.
// Direction-separated traffic keys MUST be established by an authenticated
// handshake before using the codec. Using a shared key across directions is
// UNSAFE because it can cause nonce reuse.
func NewSessionCodec(aead cipher.AEAD, connectionID uint64, epoch uint16) (*SessionCodec, error) {
	if aead == nil || aead.NonceSize() != 12 || aead.Overhead() != AuthTagSize {
		return nil, ErrBadAEAD
	}
	return &SessionCodec{aead: aead, connectionID: connectionID, epoch: epoch}, nil
}

// Nonce is epoch (2 bytes), two reserved zero bytes, then counter (8 bytes).
// Uniqueness relies on a new traffic key for each direction and epoch.
func packetNonce(epoch uint16, counter uint64) [12]byte {
	var nonce [12]byte
	binary.BigEndian.PutUint16(nonce[:2], epoch)
	binary.BigEndian.PutUint64(nonce[4:], counter)
	return nonce
}

// Seal encrypts a payload and authenticates the entire fixed header.
// It rejects counter rollover; a production transport must rekey earlier.
func (s *SessionCodec) Seal(plaintext []byte) ([]byte, error) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	if s.exhausted {
		return nil, ErrCounterExhausted
	}
	counter := s.nextCounter
	if counter == math.MaxUint64 {
		s.exhausted = true
	} else {
		s.nextCounter++
	}
	header := Header{ConnectionID: s.connectionID, KeyEpoch: s.epoch, Counter: counter}.MarshalBinary()
	nonce := packetNonce(s.epoch, counter)
	return s.aead.Seal(header, nonce[:], plaintext, header), nil
}

// Open authenticates the packet before changing replay state. All receive
// operations are serialized to prevent concurrent duplicate acceptance.
func (s *SessionCodec) Open(packet []byte) ([]byte, error) {
	header, err := ParseHeader(packet)
	if err != nil {
		return nil, err
	}
	if header.ConnectionID != s.connectionID || header.KeyEpoch != s.epoch {
		return nil, ErrAuthentication
	}
	s.recvMu.Lock()
	defer s.recvMu.Unlock()
	nonce := packetNonce(header.KeyEpoch, header.Counter)
	plaintext, err := s.aead.Open(nil, nonce[:], packet[HeaderSize:], packet[:HeaderSize])
	if err != nil {
		return nil, ErrAuthentication
	}
	if !s.replay.Accept(header.Counter) {
		return nil, ErrReplay
	}
	return plaintext, nil
}
