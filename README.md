# NetherVPN

**NetherVPN** — experimental self-hosted encrypted VPN using a custom versioned
UDP packet format and standard, reviewed cryptographic primitives (Noise IK,
X25519, ChaCha20-Poly1305, BLAKE2s). **Pre-alpha: not audited; do not use for
privacy-critical traffic or production systems.**

## Current capabilities

- Linux gateway and Linux CLI/desktop (Tkinter) client with authenticated TUN, opt-in full IPv4 tunnel, systemd-resolved DNS and policy-routing leak protection.
- Android client with foreground Android VpnService, imported profiles, encrypted
  Keystore storage, keepalives, encrypted packet forwarding and reconnect.
- Multiple independently authorized clients on one gateway; return packets are
  delivered to the assigned client IP, not just the last connected client.
- `sudo nether-admin` opens a Russian server menu to create/list/revoke universal Android/Linux `.conf` identities,
  allocates a free tunnel IPv4 and registers each client's public key.
- Android **and Linux** select Full/Split IPv4 tunnel and DNS locally in their UIs; generated server profiles contain no routing choices.
- Linux desktop supports optional boot autoconnect via systemd. Android Always-on and block-without-VPN are OS-controlled.
- Optional scoped IPv4 NAT/forwarding on the gateway, with opt-in systemd
  boot persistence. The server's default route remains unchanged.
- Android debug APK built by GitHub Actions and published as a pre-release.

**Get started:** [Server management](docs/ADMIN.md) · [Full-tunnel setup](docs/FULL-TUNNEL.md) ·
[Android client](android/README.md) ·
[Releases](https://github.com/WWWorm92/NetherVPN/releases) ·
[Security](docs/SECURITY.md)

### Linux build

Requires Go 1.23+.

```bash
go test -race ./...
go build ./cmd/nether-server
go build ./cmd/nether-client
go build ./cmd/nether-admin
```

### Current security limitations

No independent security review, no production-grade key rotation, no complete
handshake replay/DoS hardening or formal Android↔Go interoperability test on
real devices. Full tunnel currently supports **IPv4 only**; IPv6 apps may not
work because IPv6 is deliberately blocked, not leaked. Android's system-wide
*Block connections without VPN* option is needed to prevent direct traffic
while disconnected or reconnecting. DNS requires server-side NAT. VPN egress
and firewall changes need explicit root action and real-network testing.

The existing `examples/server.conf` and `examples/client.conf` show the
legacy point-to-point format. For multiple clients, migrate server
`Address = 10.77.0.1/30` to `10.77.0.1/24` (after verifying no address
overlap) and create new profiles with `nether-admin`; do not share
one private key between devices.

## License

License decision for the project remains pending. The Noise-Java third-party
dependency has its own MIT license.
