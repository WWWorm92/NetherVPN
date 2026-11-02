# Security model (draft)

NetherVPN is experimental. **Do not use for real private traffic yet.**

## Assets

- Client and server identity keys
- Session keys
- Private traffic contents and metadata
- Route/DNS configuration and administrative credentials

## Threats to consider

Active interception, replay, packet injection, downgrade, malicious peer, stolen client credentials, key compromise, DoS, rogue controller, DNS/IP leaks and side channels.

## Candidate cryptography

Use a maintained implementation of Noise IK (X25519 / ChaChaPoly / BLAKE2s), after careful evaluation. This is a **design candidate**, not a completed implementation.

## Invariants before an alpha tunnel

1. Cryptographically authenticate peer identities and negotiated parameters.
2. Never reuse a nonce with the same AEAD key.
3. Authenticate all security-relevant headers as associated data.
4. Replay protection and explicit counter rollover handling.
5. Rekey well before exhaustion, with bounded old-key acceptance.
6. Bind client authorization to a unique device identity.
7. Fail closed on invalid frames, keys, versions and configuration.
8. Prevent DNS/IPv6 escape when full-tunnel protection is requested.
9. No sensitive keys or payloads in logs, diagnostics or crash reports.
10. Fuzz packet parsing, run race tests and request independent review.

## Disclosure

Until a security contact is configured, report issues privately to the repository owner. Avoid posting exploitable details in public issues.
