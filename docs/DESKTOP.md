# NetherVPN Linux desktop — local connection settings

**Experimental.** The same universal `.conf` from `sudo nether-admin` works
in both Android and Linux. Neither the server nor the identity config decides
whether Full Tunnel is enabled.

## Installation

```bash
sudo apt install python3-tk polkitd pkexec iproute2 systemd-resolved
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-desktop.sh
python3 gui/nethervpn.py
```

Import your generated `.conf`, select it, then choose in the **Linux GUI**:

- **Весь IPv4 через VPN**: system-wide Full Tunnel, DNS over encrypted VPN,
  IPv6 blocked until supported. Server needs NAT/forwarding.
- **Только адрес VPN-сервера**: Split Tunnel, only the assigned server peer IP
  routed through the tunnel.
- **DNS**: numeric IPv4 server (default 1.1.1.1), used in Full Tunnel.
- **Подключаться при запуске Linux**: optional systemd `enable`, applied at
  the next connection. Disabling autostart does not disconnect current VPN.

Click **Сохранить настройки** and then reconnect to apply. The Linux GUI now requests **one polkit authorization for Connect** and **one for Disconnect**; a root-owned helper performs each operation as a single transaction. If activation fails, it restores the previous saved profile/settings instead of leaving a partially changed configuration. Routing preferences
are written to a separate user-owned `~/.config/nethervpn/profiles/NAME.prefs`
file with mode 0600; they are **not** inserted into `.conf`. When you connect,
the GUI snapshots this file as
`/etc/nethervpn/client-settings.conf`, mode 0600, and systemd starts
`nether-client -config /etc/nethervpn/client.conf -settings
/etc/nethervpn/client-settings.conf`. Previous profiles containing
`FullTunnel` and `DNS` are readable for migration. New GUI settings take
precedence. Settings can be different for each imported identity.

### Routing behavior

The full-tunnel Linux client reserves routing table 51830 and priorities
12100/12101; the server public IPv4 is excluded from the VPN to keep its
encrypted UDP path alive. The existing host main default route is not
overwritten. With an authenticated TUN the IPv4 default route is inserted in
table 51830 and DNS configured via `resolvectl`. In reconnect periods an
unreachable fallback route blocks IPv4/IPv6 from escaping. The fail-closed
policy is integral to Linux Full Tunnel; disabling it would allow leaks.

**The policy is not a guaranteed persistent host firewall Kill Switch across
SIGKILL, power loss, or all systemd restarts.** After graceful disconnect the
VPN routes and DNS overrides are removed. On a forced termination, rules may
remain and block Internet. Do not enable host-wide routing from an SSH-only
session without alternate console access.

To inspect, click **«Проверить»** in the Linux GUI. The button shows systemd state, IPv4 route and recent service errors without requesting another password. Or run:

```bash
bash scripts/diagnose-full-tunnel.sh client
sudo journalctl -u nethervpn-client -n 80 --no-pager
ip -4 route show table 51830
ip -4 rule show
resolvectl status nvpn0
curl -4 https://api.ipify.org
```

To stop gracefully:

```bash
sudo systemctl stop nethervpn-client
```

For exceptional manual recovery **after verifying the reserved rules belong to
NetherVPN**:

```bash
sudo ip -4 rule del pref 12100
sudo ip -4 rule del pref 12101
sudo ip -6 rule del pref 12101
sudo ip -4 route flush table 51830
sudo ip -6 route flush table 51830
```

Avoid recovery commands if table 51830 belongs to another service.
Do not assume a successful CI build proves tested VPN functionality or privacy.

## Full Tunnel troubleshooting

If Split Tunnel works but Full Tunnel does not, check these separately:

1. **Linux:** `ip -4 route get 1.1.1.1` should display `dev nvpn0`, with source IP equal to the assigned client's `Address`. Missing TUN means a failed handshake or interface setup; wrong route means routing configuration failed. New code rejects a mismatched kernel route rather than reporting Full Tunnel as active.
2. **DNS:** `systemctl is-active systemd-resolved` should return `active`; `resolvectl status nvpn0` should show the configured DNS server. The desktop installer now warns when systemd-resolved is absent or inactive.
3. **Gateway:** `sudo nethervpn-full-tunnel status eth0 10.77.0.0/24 nvpn0` should show IPv4 forwarding, MASQUERADE and both scoped FORWARD rules. Replace `eth0` with the gateway's actual WAN interface; run `bash scripts/diagnose-full-tunnel.sh server` for read-only diagnostics.
4. **Firewall:** verify input UDP 51830 and IPv4 forwarding in all active firewalls, including UFW/nftables/cloud ACLs. NAT configuration alone does not override separate drop rules.

If the Linux workstation is also running `nethervpn-server`, do not enable host-wide Full Tunnel there. The desktop helper now refuses this configuration because it could disrupt gateway routing. It also refuses sharing the same TUN between server and client.

**Not yet end-to-end validated:** the fixes add route checks and correct default-route source selection, but a successful CI test alone does not establish the precise cause on a specific machine or confirm live Internet egress.

## Fresh Linux policy tables and pre-existing TUN interfaces

On a new Linux installation, `ip -4 route show table 51830` and
`ip -6 route show table 51830` may exit with status 2 and print
`FIB table does not exist`. **This is expected before the first Full
Tunnel connection.** NetherVPN now interprets this exact response as an
available table and creates its own fail-closed routes. Other errors (for
example, operation not permitted) still prevent startup. The systemd client
unit is rate-limited to five failed starts per minute to avoid log spam and
infinite restart cycles.

If `ip -br addr show nvpn0` shows an address **before** starting the new
client, another VPN session or persistent TUN already owns the name. Do not
blindly delete the interface, particularly if it is used for remote access.
Check the active gateway/client services and TUN users:

```bash
systemctl is-active nethervpn-server nethervpn-client
sudo fuser -v /dev/net/tun
ip -br addr show nvpn0
```

Stop the other session through its own service manager, or edit the client's
`[Interface] Name` to an unused TUN interface (for example `nvpn1`) and
reimport the profile. A name change does not modify either cryptographic key.
The desktop client refuses to change system routes if its TUN name is already
present, instead of failing later with `TUNSETIFF: device or resource busy`.

After deploying the fix from `main`:

```bash
cd ~/NetherVPN
git pull --ff-only
sudo systemctl stop nethervpn-client
bash scripts/install-desktop.sh
sudo systemctl reset-failed nethervpn-client
python3 gui/nethervpn.py
```

Do not enable Full Tunnel until the initial table check and existing TUN
conflict are cleared. A separate issue such as gateway NAT or DNS can still
require diagnosis after the client begins forwarding packets.

## Reconnect loop after successful handshake: invalid IP packet

A previous Linux Full Tunnel build repeatedly showed
`client: authenticated handshake complete`, followed immediately by
`client: tunnel: invalid IP packet; retrying ...`. TUN is a raw kernel
packet interface: it may emit IPv6, malformed/truncated packets or IPv4
whose source is not the assigned VPN address. Those packets must **not**
be sent through NetherVPN, but they must also **not** terminate the
authenticated session.

The desktop client now drops invalid, oversized, unsupported IPv6 and
source-spoofed outbound packets and keeps the same session up. It emits
rate-limited counters without recording packet payloads, for example:

```text
client: dropped outbound TUN packet: IPv6 blocked in IPv4-only Full Tunnel (40 bytes, 1 dropped); keeping session alive
```

Transport I/O failures, a genuinely silent authenticated peer and crypto
errors still trigger reconnection. Split Tunnel applies the same
source-address protection. The server also rejects spoofed client sources.

To install the new client and review status:

```bash
cd ~/NetherVPN
git status --short
git pull --ff-only
sudo systemctl stop nethervpn-client
bash scripts/install-desktop.sh
python3 gui/nethervpn.py
```

If `git pull` reports local changes, review them with `git diff` and back
them up (for example `git stash push -u -m "local-pre-update"`) before
updating; do not discard private files or custom work. Check afterward:

```bash
sudo journalctl -u nethervpn-client -n 40 --no-pager
ip -br addr show nvpn0
ip -4 route get 1.1.1.1
curl -4 --max-time 8 https://api.ipify.org
```

Your network may also contain an earlier-priority IPsec/AmneziaWG routing
rule (for example preference 220), which can influence routing independently
of NetherVPN. This fix addresses the packet-triggered reconnect loop, **not**
all coexistence, DNS, upstream gateway forwarding/NAT or firewall issues.
When confirming Full Tunnel, first verify a stable TUN and a kernel default
route through `nvpn0`; then test the real external IPv4 and DNS.

## Redesigned Linux dashboard (0.5 visual update)

The Tkinter dashboard is now larger, vertically scrollable, and uses the same
midnight-navy/mint design language as Android. It contains a dedicated
profile sidebar, connection hero, status indicator, one-click actions, traffic
cards, Full/Split selection cards, local DNS, boot autoconnect and event
journal. The dashboard supports smaller laptop displays without hiding the
bottom settings and retains one polkit authorization per connect/disconnect.

The status indicator is linked to the **systemd client state and actual TUN
interface**, rather than marking a selected radio button or a successful
`pkexec` call as proof of connectivity. When reopened while the VPN is
already running, the UI indicates an existing background systemd connection.
For a confirmed encrypted session use the service log and test the real
route/IP; TUN existence alone is not independent cryptographic verification.

Install or refresh the desktop (existing secret profiles are preserved):

```bash
cd ~/NetherVPN
git pull --ff-only
sudo systemctl stop nethervpn-client
bash scripts/install-desktop.sh
python3 gui/nethervpn.py
```

To see full encrypted tunnel status and dropped IPv6 packets:

```bash
sudo journalctl -u nethervpn-client -n 40 --no-pager
ip -4 route get 1.1.1.1
curl -4 --max-time 8 https://api.ipify.org
```
