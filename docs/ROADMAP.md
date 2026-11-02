# Roadmap

All milestones require automated tests and reproducible instructions.

## M0: Repository bootstrap
- [x] Project goals, architecture and threat-model drafts
- [x] Go module and non-functional server/client entry points
- [x] CI for Go tests, vet and formatting reporting
- [ ] Decide Apache-2.0 vs another license

## M1: Protocol specification
- [x] Draft fixed DATA header layout and codec tests
- [x] Draft 64-packet replay window and tests
- [x] AEAD packet codec with authenticated header, distinct epoch/counter nonce and post-auth replay check
- [ ] Complete authenticated handshake wire format and security review
- [ ] Secure per-direction traffic key schedule, counter limits and automatic rekey
- [ ] Production cipher suite, test vectors and interoperability review

## M2: Linux VPN prototype
- [ ] Authenticated server/client sessions
- [ ] Linux TUN and basic IPv4 forwarding
- [ ] NAT and optional full tunnel
- [ ] Network namespace integration tests
- [ ] Safe error handling and reconnect

## M3: Public alpha quality
- [ ] IPv6 and DNS leak controls
- [ ] Multi-device policy, enrollment and revocation
- [ ] Fuzzing, throughput and loss benchmarks
- [ ] Documented installation and rollback

## M4: Management
- [ ] Authenticated API and web UI
- [ ] SQLite standalone and PostgreSQL cluster mode
- [ ] Server inventory, audit events and key lifecycle

## M5: Cross-platform and transport
- [ ] Windows and Android applications
- [ ] QUIC and TLS/TCP adapters
- [ ] Cross-network migration, platform kill switches
- [ ] Security audit before production recommendation
