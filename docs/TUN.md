# Linux TUN backend and encrypted IP bridge (experimental)

The Linux `internal/tun` package opens `/dev/net/tun` using `TUNSETIFF`. The `internal/tunnel` package supports bounded packet forwarding using `PumpToUDP` and `PumpFromUDP` with mandatory packet policy callbacks.

The server-side policy must verify that the source IP address of decrypted packets matches the authorized address for the particular session. `VerifySource` implements this comparison for IPv4 and IPv6. The policy callback must come from a trusted configuration, not a client-provided claim.

**Not a production VPN:** these pumps are for a *single peer* and have not yet been connected to persistent executable client/server processes, dynamic session dispatch, routing, DNS or NAT. `PumpToUDP` needs an explicitly closed reader to stop if blocked. IPv4 fragments, IPv6 extension headers, offload behavior and MTU details need further review. Client roaming is not supported.

No interfaces, addresses, routes, DNS, firewall or NAT configuration is modified automatically. Privileged e2e testing should use separate Linux network namespaces.
