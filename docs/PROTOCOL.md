# NetherVPN Wire Protocol — working draft 0.1

**Status: UNSTABLE DESIGN DRAFT, not a deployed secure protocol.** No interoperability or security guarantees.

## Scope and separation

The authenticated data plane is separate from management. A device is provisioned with its own private identity and a trusted server public key. The management API cannot silently substitute a different server identity. Transport adapters MUST NOT alter the cryptographic peer identity.

## Handshake candidate (not yet implemented)

Use a maintained Noise library with the `Noise_IK_25519_ChaChaPoly_BLAKE2s` pattern. A client knows the server static public key before beginning handshake. Each device has its own static key. The protocol must bind a versioned application-specific prologue to the transcript and formally define server authorization, identity rotation, timestamp/anti-replay or challenge strategy, and resource-exhaustion resistance before any handshake implementation ships. Reject incompatible negotiation; never fall back to plaintext.

## UDP data packet, post-handshake candidate

All integers are unsigned, network byte order (big-endian).

| Offset | Size | Field |
|---|---:|---|
| 0 | 1 | Version (= 1 for this draft) |
| 1 | 1 | Type (= 3 for DATA) |
| 2 | 8 | Connection ID |
| 10 | 2 | Key epoch |
| 12 | 8 | Packet counter |
| 20 | variable | AEAD ciphertext, including 16-byte authentication tag |

The fixed 20-byte header MUST be included in the AEAD associated data (AAD); no unauthenticated routing, policy or security decisions can be made from it. The receiver resolves a candidate session from the connection ID but accepts the data only after successful authentication and anti-replay verification. A collision-safe connection ID allocation strategy is mandatory.

Packet counters are per-direction and per-epoch. Counter reuse with the same key is forbidden. The nonce derivation and key schedule **remain undefined**; DATA packets are not safe to send until they are formally specified and implemented. The connection ID must not be treated as user authentication.

Packet-size validity: data packets require at least 20 header bytes + a 16-byte AEAD tag. An authenticated session establishes an MTU policy, including IPv6 behavior, and avoids uncontrolled IP fragmentation.

## Receive state

Reject unknown protocol versions and types; reject malformed lengths. Determine candidate session; authenticate AEAD before modifying accepted replay state. Maintain an independent 64-packet replay window per key epoch and direction. Do not consume replay state for failed authentication. Reject sequence numbers outside the receive window. Rekey and overlapping epochs require bounded acceptance with explicit expiry. Send counters must not wrap.

## Message types (reserved)

1 = INIT, 2 = RESPONSE, 3 = DATA, 4 = CONTROL. Only DATA framing is defined here. The handshake wire framing and all control messages are **not** yet specified.

## Tests and subsequent work

- Header marshal/unmarshal rejects truncated buffers, wrong version and wrong type.
- Replay window rejects duplicates and packets older than 64 seen sequence positions.
- Test vectors, parser fuzzing, authenticated packet tests, counter exhaustion tests, packet loss/reordering, handshake failures and rekey integration tests are needed.
- No VPN tunnel, handshake or encryption has been implemented as of this document.
