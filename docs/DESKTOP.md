# NetherVPN desktop (Linux prototype)

A minimal Tk desktop app with profile import, connect, disconnect and connection process status.

The profile syntax looks like WireGuard INI, but **it is not a WireGuard tunnel**. Only one authorized remote peer per process. No full-tunnel default route, DNS, NAT, reconnection, or auto-start yet.

## Install
On Ubuntu: `sudo apt install python3-tk policykit-1 iproute2`.
Build from repo: `go build -o bin/nether-client ./cmd/nether-client`.
Launch **as regular desktop user**, not root: `python3 gui/nethervpn.py`.
Import a locally created .conf profile. Connect asks for admin privileges through polkit.

Secrets are stored in `~/.config/nethervpn/profiles/` (mode 0700 directory, mode 0600 files); don't commit them. CLI `-config` reads secrets from disk rather than argv. Root-polkit privileges are still broad; production GUI needs a narrow privileged service and audited policy.

Server: `sudo ./bin/nether-server -config /etc/nethervpn/server.conf`.
A profile's Address is applied via `ip addr replace` on the TUN interface once handshake succeeds. An isolated /30 network is recommended. Internet routing is deliberately NOT enabled.

Known prototype limitations: one session per server process; 15 second initial handshake timeout; no handshake retransmissions; no robust teardown; no multi-client TUN dispatcher; no security audit. Do not replace a production VPN with this prototype.
