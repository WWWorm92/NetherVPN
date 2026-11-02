# Experimental Linux client and server

Two CLI programs now call Noise IK UDP handshake, open TUN and launch bidirectional forwarding loops. These executables are **NOT safe to expose to the internet**.

Build with `go build ./cmd/nether-server` and `go build ./cmd/nether-client`.

Both programs accept hexadecimal private keys on the command line **for isolated test use only**. This exposes secrets to process listings and shell history. Secure file-based key storage and a device enrollment flow MUST replace it before real deployments. The repository contains no key material.

The prototype does not automatically configure IP addresses, DNS, routes, firewall, forwarding, NAT, rekey or multi-client routing. Run only inside disposable Linux network namespaces with manual isolated TUN address assignment. Server currently supports a single active client and times out waiting for the first handshake.

Known security gaps: replayed handshake initiations, retransmission, absence of rekey and server session expiry, no complete DoS protections, source-address policy on the receiving path only. UDP authentication is based on the Noise IK static keys but this is not a production VPN.
