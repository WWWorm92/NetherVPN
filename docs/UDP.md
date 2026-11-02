# UDP transport prototype

The UDP module now includes loopback tests for a full **on-wire Noise IK exchange**, followed by encrypted DATA packets across OS UDP sockets.

The server sends a randomly chosen connection ID and an initial epoch in the **authenticated encrypted Noise IK response payload**. These session parameters are not accepted without a valid server-authenticated response.

**Experimental only. Never expose this listener on the public internet.** Current AcceptHandshake handles a single client. There is no replay cache for initiation messages, anti-amplification cookie mechanism, rate limiting, handshake retransmission, session eviction, rekey, or robust denial-of-service protection. A captured initiation could create redundant sessions. Fail-closed validation needs additional review. Network source IP is a transport hint and not identity.

Next: enforce replay cache/rate-limiting, add timers and retry negotiation; establish multi-peer session management, key rotation and TUN integration.

No end-user VPN tunnel exists yet.
