//go:build linux

package tun

import (
	"errors"
	"testing"
)

func TestValidName(t *testing.T) {
	for _, name := range []string{"nvpn0", "n-vpn_0", "VPN123"} {
		if !ValidName(name) { t.Errorf("valid name rejected: %q", name) }
	}
	for _, name := range []string{"", "abcdefghijklmnop", "../tun0", "with space", "vpn:1", "рус"} {
		if ValidName(name) { t.Errorf("invalid name accepted: %q", name) }
	}
}

func TestOpenRejectsBadNamesWithoutPrivileges(t *testing.T) {
	if _, err := Open("../bad"); !errors.Is(err, ErrName) {
		t.Fatalf("expected ErrName, got %v", err)
	}
}
