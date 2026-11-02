# Architecture (draft)

## Data plane

Linux TUN -> tunnel engine -> authenticated session -> UDP transport -> remote peer.

**No production code exists for this path yet.**

The data plane is distinct from the HTTPS management API. Existing sessions must not depend on management API uptime.

## Control plane

Initially, standalone server: local device policy and configuration; later optional central controller with asynchronous policy distribution and audited device revocation.

Device private keys are generated and retained on the device, not uploaded to the controller. Each device requires a unique identity.

## Transport

1. UDP native datagrams.
2. QUIC over UDP (not a fallback when UDP is blocked).
3. TLS 1.3 over TCP as a separate transport, with an explicit framing protocol.

Transport changes must never downgrade identity validation or confidentiality. Avoid nesting unbounded queues or TCP-over-TCP as an unqualified default.

## Work packages

- Specify handshake transcripts and server identity pinning.
- Define authenticated header fields, monotonic counters and replay windows.
- Implement TUN lifecycle, IP routing, DNS and firewall isolation.
- Test reconnect, key expiration, rekey and state recovery.
- Validate MTU/fragmentation behavior, IPv6 parity and secure defaults.
- Add e2e tests in Linux network namespaces.

See [SECURITY.md](SECURITY.md) for threat model.
