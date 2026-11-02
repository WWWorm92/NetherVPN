# Deployment notes

Ubuntu server: build with `go mod tidy && go test ./...`, then run `bash scripts/install-server.sh`, stop any manually started nether-server and explicitly enable with `sudo systemctl enable --now nethervpn-server`. Inspect `sudo journalctl -u nethervpn-server -f`.

On machines with several public IPv4 addresses set `ListenAddress = 45.151.69.184` in `/etc/nethervpn/server.conf`. Client should pin the server public key in its profile.

Client: `go build -o bin/nether-client ./cmd/nether-client` and `python3 gui/nethervpn.py`. Retries use bounded backoff, but detecting a silently dead established session is not yet implemented.

Only directly connected TUN subnet routing is enabled. Full-tunnel routing is deliberately not available: production setup needs endpoint exclusion, safe DNS, NAT/forwarding, IPv6, rollback, and kill-switch logic. Do not replace a working default route with `ip route replace default dev nvpn0`.

Not production-ready: handshake replay defenses, rate limits, rekey, multi-client dispatch, auditable privilege separation and protocol security review are needed.
