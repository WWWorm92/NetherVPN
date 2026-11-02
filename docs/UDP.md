# UDP transport prototype

The UDP module currently provides a **loopback integration test**, not a deployable VPN endpoint. The test completes a Noise IK handshake in memory, establishes two directional encrypted sessions, sends an encrypted datagram over real OS UDP sockets and authenticates its encrypted reply.

**Critical remaining steps before deploying across a network:**

- Perform handshake on the wire with framed INIT / RESPONSE messages, bounded retransmission and handshake replay protection.
- Negotiate connection IDs and epochs *inside the authenticated transcript* rather than supplying them manually.
- Implement identity provisioning, revocation and per-peer session tables. Do not use a single shared Session for multiple clients.
- Make source-address validation and roaming a deliberate policy, not an authentication substitute.
- Add cancellation-safe sockets, read timeouts, anti-DoS measures and bounded queues.
- Derive fresh session keys across reboots, implement rekey, idle timeout, and stale-session expiry.
- Implement Linux TUN only after session lifecycle and key agreement are specified.

No working VPN tunnel or on-wire handshake exists.
