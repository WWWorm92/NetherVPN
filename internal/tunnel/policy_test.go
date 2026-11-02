package tunnel

import (
	"errors"
	"net/netip"
	"testing"
)

func TestVerifySourceIPv4(t *testing.T) {
	packet := ipv4() // 10.77.0.2 -> 10.77.0.1
	if err := VerifySource(packet, netip.MustParseAddr("10.77.0.2")); err != nil {
		t.Fatal(err)
	}
	if err := VerifySource(packet, netip.MustParseAddr("10.77.0.3")); !errors.Is(err, ErrUnauthorizedIP) {
		t.Fatalf("spoofed address accepted: %v", err)
	}
}

func TestVerifySourceIPv6(t *testing.T) {
	p := make([]byte, 40)
	p[0] = 0x60
	copy(p[8:24], netip.MustParseAddr("fd77::2").AsSlice())
	copy(p[24:40], netip.MustParseAddr("fd77::1").AsSlice())
	if err := VerifySource(p, netip.MustParseAddr("fd77::2")); err != nil {
		t.Fatal(err)
	}
	if err := VerifySource(p, netip.MustParseAddr("fd77::3")); !errors.Is(err, ErrUnauthorizedIP) {
		t.Fatalf("spoofed address accepted: %v", err)
	}
}
