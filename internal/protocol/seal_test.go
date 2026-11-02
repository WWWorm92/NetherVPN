package protocol

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"sync"
	"testing"
)

func testAEAD(t *testing.T) cipher.AEAD {
	t.Helper()
	block, err := aes.NewCipher(bytes.Repeat([]byte{0x42}, 32))
	if err != nil {
		t.Fatal(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	return aead
}

func TestSealOpenAndReplay(t *testing.T) {
	// AES-GCM is used here solely as a standard-library test AEAD. The
	// production cipher suite remains a protocol design decision.
	aead := testAEAD(t)
	sender, err := NewSessionCodec(aead, 37, 4)
	if err != nil {
		t.Fatal(err)
	}
	receiver, err := NewSessionCodec(aead, 37, 4)
	if err != nil {
		t.Fatal(err)
	}
	p1, err := sender.Seal([]byte("first"))
	if err != nil {
		t.Fatal(err)
	}
	p2, err := sender.Seal([]byte("second"))
	if err != nil {
		t.Fatal(err)
	}
	got, err := receiver.Open(p2)
	if err != nil || string(got) != "second" {
		t.Fatalf("second packet: %q %v", got, err)
	}
	got, err = receiver.Open(p1)
	if err != nil || string(got) != "first" {
		t.Fatalf("reordered packet: %q %v", got, err)
	}
	if _, err := receiver.Open(p1); !errors.Is(err, ErrReplay) {
		t.Fatalf("duplicate accepted: %v", err)
	}
}

func TestTamperDoesNotPoisonReplayState(t *testing.T) {
	aead := testAEAD(t)
	sender, _ := NewSessionCodec(aead, 10, 1)
	receiver, _ := NewSessionCodec(aead, 10, 1)
	packet, _ := sender.Seal([]byte("sensitive"))
	tampered := append([]byte(nil), packet...)
	tampered[len(tampered)-1] ^= 1
	if _, err := receiver.Open(tampered); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("tampering not rejected: %v", err)
	}
	headerTampered := append([]byte(nil), packet...)
	headerTampered[19] ^= 1
	if _, err := receiver.Open(headerTampered); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("header tampering not rejected: %v", err)
	}
	if _, err := receiver.Open(packet); err != nil {
		t.Fatalf("valid packet rejected after tampering: %v", err)
	}
}

func TestConcurrentReplayAcceptance(t *testing.T) {
	aead := testAEAD(t)
	sender, _ := NewSessionCodec(aead, 7, 1)
	receiver, _ := NewSessionCodec(aead, 7, 1)
	packet, _ := sender.Seal([]byte("one"))
	var wg sync.WaitGroup
	results := make(chan error, 16)
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := receiver.Open(packet)
			results <- err
		}()
	}
	wg.Wait()
	close(results)
	successes := 0
	for err := range results {
		if err == nil {
			successes++
		} else if !errors.Is(err, ErrReplay) {
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if successes != 1 {
		t.Fatalf("accepted duplicate %d times", successes)
	}
}

func TestRejectWrongConnectionAndEpoch(t *testing.T) {
	aead := testAEAD(t)
	sender, _ := NewSessionCodec(aead, 7, 1)
	wrongID, _ := NewSessionCodec(aead, 8, 1)
	wrongEpoch, _ := NewSessionCodec(aead, 7, 2)
	packet, _ := sender.Seal([]byte("data"))
	for _, receiver := range []*SessionCodec{wrongID, wrongEpoch} {
		if _, err := receiver.Open(packet); !errors.Is(err, ErrAuthentication) {
			t.Fatalf("mismatched session accepted: %v", err)
		}
	}
}
