package tunnel

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestPumpFromUDPCancellationAndSpoofProtection(t *testing.T) {
	client, server := pair(t)
	local := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}
	sender, err := net.ListenUDP("udp4", local)
	if err != nil { t.Fatal(err) }
	defer sender.Close()
	receiver, err := net.ListenUDP("udp4", local)
	if err != nil { t.Fatal(err) }
	defer receiver.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 350 * time.Millisecond)
	defer cancel()
	var written bytes.Buffer
	done := make(chan error, 1)
	go func() {
		done <- PumpFromUDP(ctx, &written, receiver, sender.LocalAddr().(*net.UDPAddr), server, DefaultMTU,
			func(packet []byte) error { return VerifySource(packet, netip.MustParseAddr("10.77.0.2")) })
	}()
	bad := ipv4()
	bad[15] = 3
	encrypted, err := client.Seal(bad)
	if err != nil { t.Fatal(err) }
	if _, err := sender.WriteToUDP(encrypted, receiver.LocalAddr().(*net.UDPAddr)); err != nil { t.Fatal(err) }
	time.Sleep(30 * time.Millisecond)
	good, err := client.Seal(ipv4())
	if err != nil { t.Fatal(err) }
	if _, err := sender.WriteToUDP(good, receiver.LocalAddr().(*net.UDPAddr)); err != nil { t.Fatal(err) }
	if err := <-done; !errors.Is(err, context.DeadlineExceeded) { t.Fatalf("pump exit: %v", err) }
	if !bytes.Equal(written.Bytes(), ipv4()) { t.Fatalf("unexpected packets: %x", written.Bytes()) }
}
