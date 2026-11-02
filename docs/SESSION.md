# Noise-to-datagram session prototype

The `internal/session` package connects completed Noise IK directional cipher states to the existing 20-byte NetherVPN DATA header.

Each direction uses its own key from Noise Split and a monotonically increasing uint64 counter as the low-level Noise ChaChaPoly nonce. The entire 20-byte header is authenticated as associated data. The receiver verifies the AEAD tag before updating the 64-packet replay window. Reordered packets inside the window are accepted.

A separate Noise handshake is mandatory for every new session, including after process restart. Never reuse keys with reset counters. Never use the same key for both directions. Existing session keys and the replay window must not be reused across separate connections.

## Missing security-critical pieces

- Connection ID assignment must be bound to the authenticated transcript and negotiated between peers. Today tests explicitly supply a matching ID and epoch.
- Implement handshake message framing, retries, anti-amplification and duplicate-handshake protection.
- Define and implement time/volume-based rekey and epoch transitions.
- Limit memory, rate-limit unauthenticated initiations, and enforce client revocation.
- Add network transport and TUN integration only after these mechanisms are reviewed.

No server socket or VPN tunnel has been activated. This is a tested in-process demonstration only.
