# Roadmap

All milestones require automated tests and reproducible instructions.

## M0: Repository bootstrap
- [x] Project goals, architecture and threat-model drafts
- [x] Go module and non-functional server/client entry points
- [x] CI configuration for Go tests, vet and formatting (run results not yet confirmed)
- [ ] Decide Apache-2.0 vs another license

## M1: Protocol specification
- [x] Draft fixed DATA header layout and codec tests (NOT authenticated)
- [x] Draft 64-packet replay window and tests (caller must authenticate first)
- [ ] Complete handshake wire format and security review
- [ ] Key schedule, nonce derivation, rekey and forward secrecy analysis
- [ ] Security and interoperability test vectors

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
