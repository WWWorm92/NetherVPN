# NetherVPN

**NetherVPN** is an experimental, self-hosted, multi-user VPN platform with a versioned wire protocol.

> [!WARNING]
> Pre-alpha research software. There is no working VPN tunnel yet. Do not use to protect sensitive traffic.

## Design goals

- Independently specified network protocol; **no custom cryptographic primitives**.
- Linux VPN gateway and CLI client first; Windows and Android later.
- UDP transport first, with optional QUIC and TLS/TCP adapters.
- Device-level identity, enrollment, revocation, and multi-user management.
- Standalone deployment first, with a separate control plane for multi-server deployments.
- IPv4/IPv6, split/full tunnel, reconnect, anti-replay, key rotation, and leak protection as explicit milestones.
- Measured performance, fuzzing, interoperability tests and a security review before stable release.

## Project status

Version: **0.1.0-dev** — architecture and executable placeholders only.

## Layout

```text
cmd/nether-server/   Linux gateway entry point (placeholder)
cmd/nether-client/   Linux client entry point (placeholder)
internal/protocol/   Protocol wire constants and future parser
docs/               Architecture, security and milestones
```

## Build

Requires Go 1.23 or later.

```sh
go test ./...
go build ./cmd/nether-server
go build ./cmd/nether-client
```

The binaries intentionally exit with a message: tunneling has not been implemented.

## Security approach

Only reviewed, established cryptographic libraries will be considered. The candidate authenticated handshake is Noise IK with X25519, ChaCha20-Poly1305 and BLAKE2s. The protocol specification must define key confirmation, authenticated packet metadata, nonce management, anti-replay windows, rekeying, identity enrollment, denial-of-service handling and error behavior **before** network traffic is enabled.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

License decision pending. Do not assume open-source permissions until a LICENSE file is added.
