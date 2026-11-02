# Linux TUN backend (experimental)

The `internal/tun` package opens `/dev/net/tun` using the Linux TUNSETIFF ioctl with IFF_TUN | IFF_NO_PI, returning an `os.File` for reading and writing raw L3 IP packets.

It **does not** change IP addresses, DNS, routing tables, NAT, firewall or forwarding configuration. It is not yet wired to the encrypted UDP session manager.

A caller needs Linux `CAP_NET_ADMIN` and a working `/dev/net/tun` device. Interface creation occurs only on an explicit call to `tun.Open`; unit tests only validate naming and do not require privileges.

Security and integration work remaining:
- Packet forwarding with bounded buffers and strict authorized-client IP mappings.
- Graceful close/cancellation, MTU discovery and no-fragmentation policy.
- Fail-closed routing and DNS safeguards.
- Linux network namespace integration tests, including IPv6 behavior.
- Replace raw ioctl ifreq implementation with a reviewed OS package if practical.
- Security review and code cleanup prior to external network testing.

The UDP handshake message markers are now 0xA1 (INIT) and 0xA2 (RESPONSE), avoiding collision with DATA version byte 1.
