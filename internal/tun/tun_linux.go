//go:build linux

// Package tun implements a Linux TUN interface without changing OS routes.
package tun

import (
	"errors"
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	iffTUN = 0x0001
	iffNoPI = 0x1000
	tunSetIFF = 0x400454ca
	ifNameSize = 16
)

// Device wraps a Linux /dev/net/tun file descriptor.
type Device struct {
	*os.File
	name string
}

var ErrName = errors.New("tun: interface name must be 1–15 ASCII letters, digits, underscore or dash")

// ValidName prevents malformed interface names and accidental truncated names.
func ValidName(name string) bool {
	if len(name) == 0 || len(name) >= ifNameSize { return false }
	for i := 0; i < len(name); i++ {
		c := name[i]
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '_' || c == '-') { return false }
	}
	return true
}

// Open creates or attaches to a TUN device. CAP_NET_ADMIN is required.
// It does not configure an IP address, routes, DNS, firewall or NAT.
func Open(name string) (*Device, error) {
	if !ValidName(name) { return nil, ErrName }
	f, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
	if err != nil { return nil, fmt.Errorf("tun: open: %w", err) }

	// Linux ifreq: 16-byte name and a 16-bit flags field followed by padding.
	// 40 bytes accommodates the largest standard ifreq union on 64-bit Linux.
	var request [40]byte
	copy(request[:ifNameSize], name)
	request[16] = byte((iffTUN | iffNoPI) & 0xff)
	request[17] = byte((iffTUN | iffNoPI) >> 8)
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(tunSetIFF), uintptr(unsafe.Pointer(&request[0])))
	if errno != 0 {
		f.Close()
		return nil, fmt.Errorf("tun: TUNSETIFF: %w", errno)
	}
	actual := request[:ifNameSize]
	end := 0
	for end < len(actual) && actual[end] != 0 { end++ }
	return &Device{File: f, name: string(actual[:end])}, nil
}

// Name returns the kernel-assigned interface name.
func (d *Device) Name() string { return d.name }
