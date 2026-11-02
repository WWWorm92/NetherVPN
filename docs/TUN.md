# Linux TUN backend (experimental)

The Linux TUN backend uses nonblocking raw syscalls instead of os.File.Read to avoid `read /dev/net/tun: not pollable` on Linux systems where Go's runtime poller cannot manage the descriptor. Both reads and writes retry EAGAIN with short delays. Closing the device interrupts the read loop.

This is a laboratory implementation; there is no route, NAT, DNS, firewall or server lifecycle configuration. Never expose the current prototype directly to untrusted networks. Verify by building and running the isolated namespace test in a disposable VM. The implementation remains subject to load and cancellation review.
