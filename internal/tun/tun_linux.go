//go:build linux

// Package tun provides a Linux TUN device without modifying host routes.
package tun

import (
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

const (
	iffTUN = 0x0001
	iffNoPI = 0x1000
	tunSetIFF = 0x400454ca
	ifNameSize = 16
)

var ErrName = errors.New("tun: interface name must be 1–15 ASCII letters, digits, underscore or dash")

// Device uses raw nonblocking syscalls to avoid Go os.File's poller on
// /dev/net/tun (some kernels report "not pollable" for that descriptor).
type Device struct {
	fd int
	name string
	closed atomic.Bool
	closeOnce sync.Once
}

func ValidName(name string) bool {
	if len(name) == 0 || len(name) >= ifNameSize { return false }
	for i := 0; i < len(name); i++ {
		c := name[i]
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '_' || c == '-') { return false }
	}
	return true
}

// Open opens a nonblocking TUN. CAP_NET_ADMIN is required.
func Open(name string) (*Device, error) {
	if !ValidName(name) { return nil, ErrName }
	fd, err := syscall.Open("/dev/net/tun", syscall.O_RDWR|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0)
	if err != nil { return nil, fmt.Errorf("tun: open: %w", err) }
	var request [40]byte
	copy(request[:ifNameSize], name)
	request[16] = byte((iffTUN | iffNoPI) & 0xff)
	request[17] = byte((iffTUN | iffNoPI) >> 8)
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(tunSetIFF), uintptr(unsafe.Pointer(&request[0])))
	if errno != 0 {
		_ = syscall.Close(fd)
		return nil, fmt.Errorf("tun: TUNSETIFF: %w", errno)
	}
	end := 0
	for end < ifNameSize && request[end] != 0 { end++ }
	return &Device{fd: fd, name: string(request[:end])}, nil
}

func (d *Device) Name() string { return d.name }

// Read waits for a packet with a nonblocking syscall. A Close interrupts
// the retry loop without requiring the runtime netpoller.
func (d *Device) Read(b []byte) (int, error) {
	for {
		if d.closed.Load() { return 0, io.ErrClosedPipe }
		n, err := syscall.Read(d.fd, b)
		if err == syscall.EINTR { continue }
		if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		if err != nil { return n, err }
		return n, nil
	}
}

// Write handles short writes and EAGAIN without relying on os.File polling.
func (d *Device) Write(b []byte) (int, error) {
	for {
		if d.closed.Load() { return 0, io.ErrClosedPipe }
		n, err := syscall.Write(d.fd, b)
		if err == syscall.EINTR { continue }
		if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		return n, err
	}
}

func (d *Device) Close() error {
	var err error
	d.closeOnce.Do(func() {
		d.closed.Store(true)
		err = syscall.Close(d.fd)
	})
	return err
}
