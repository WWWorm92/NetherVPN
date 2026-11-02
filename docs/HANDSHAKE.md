# Noise IK handshake — experimental implementation

The `internal/handshake` module uses `github.com/flynn/noise` v1.1.0 with `Noise_IK_25519_ChaChaPoly_BLAKE2s`. The domain-separating prologue is `NetherVPN/noise-ik/v1`.

1. Each client generates and stores an X25519 static identity locally.
2. The client is provisioned with an authentic, pinned X25519 server public key.
3. The initiator writes Noise IK message 1.
4. The server reads and authenticates that message and checks the revealed client static public key against an explicit allowlist.
5. Only an authorized client receives message 2. Both sides split the Noise handshake into directional cipher states.
6. The initiator must validate message 2, otherwise the handshake fails.

**Not a complete VPN:** The two returned Noise cipher states support *ordered* messages. They cannot be directly substituted as the cipher for the UDP DATA packets: packet loss/reordering and explicit counters require a separately specified transport key schedule and AEAD nonce construction.

## Remaining requirements

- Complete datagram framing, retransmissions, handshake replay mitigation and DoS resistance.
- Bind all negotiated version/cipher and connection identifiers to authenticated handshake transcripts.
- Extract/derive separate directional AEAD keys and formalize nonce management and epoch rotation.
- Validate X25519 private/public identity consistency and reject disallowed low-order public keys.
- Enforce proper key lifecycle, storage and secure device revocation.
- Security review before real-world deployment.

This component is **not production ready**.
