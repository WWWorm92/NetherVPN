# Linux TUN backend and encrypted IP bridge (experimental)

The `internal/tun` package opens `/dev/net/tun` with `TUNSETIFF` and returns an `os.File` reader/writer. No system routes, DNS, firewall, IP forwarding, interface addresses or NAT are changed automatically.

The `internal/tunnel` package provides `SendOne`, `ReceiveOne`, `EncodeIP` and `DecodeIP` to demonstrate authenticated UDP encapsulation of a bounded IP packet. CI tests use in-memory TUN-like readers/writers and loopback UDP sockets; root privileges are not needed.

**Not deployable as a VPN:** no persistent forwarding loop, session lifecycle, actual TUN integration, client source-IP authorization, routing or kill switch. UDP handshake retry, handshake replay protection beyond a memory cache, anti-amplification and rekey remain incomplete.

Future integration must enforce per-client source IP assignments before writing into TUN, retain correct IP packet length and implement MTU/error handling. Use Linux network namespaces for privileged end-to-end tests instead of reconfiguring the host.
