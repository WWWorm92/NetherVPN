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

## Compact Linux desktop (0.6 visual update)

NetherVPN 0.6 replaces the oversized 1130×790 dashboard with a compact
**410×690** window inspired by simple mobile VPN clients, but with independent
NetherVPN branding. The center is a large, keyboard-accessible circle that
connects/disconnects using the existing systemd/polkit helper. The accent ring
reflects the systemd/TUN state; no route or Noise handshake is inferred from
the button alone.

The minimal main screen shows the connection status, a local route summary,
selected server and a three-button navigation:
- **Главная** — connection circle and status.
- **Настройки** — Full/Split, IPv4 DNS, boot autoconnect, diagnostics, live
  statistics and an event log.
- **＋ Импорт** — import a universal .conf profile.

Tap the **server card** to open a separate selector for existing profiles,
import another profile, or remove a disconnected profile. No key contents
are displayed on the home screen. Status/error details and traffic counters
remain accessible from **Настройки → Статистика и диагностика**.

All existing `.conf` files and per-profile `.prefs` remain unchanged.
The Linux helper still performs one privileged authorization per connection
or disconnect, and the VPN continues running as a service when the window
closes.

```bash
cd ~/NetherVPN
git status --short
git pull --ff-only
sudo systemctl stop nethervpn-client
bash scripts/install-desktop.sh
python3 gui/nethervpn.py
```

If `git pull` is blocked by local changes, inspect/backup those changes
before updating. Do not reset private profiles or overwrite local changes.
Android 0.6 Alpha debug APK is published by the Android Actions workflow
when merged to `main`. This UI redesign does not modify VPN protocol or
gateway configuration.

## Optional passwordless desktop control (polkit)

NetherVPN normally requests a polkit authentication for each connect/disconnect
because the desktop helper writes root-owned client settings and controls a
systemd service, TUN routes and DNS. Do not run the GUI with sudo.

To opt into passwordless control **only for the currently logged-in local
desktop account and the fixed root-installed NetherVPN helper**:

```bash
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-desktop.sh
bash scripts/passwordless-desktop.sh enable
```

To revoke this permission:

```bash
bash scripts/passwordless-desktop.sh disable
```

This is not equivalent to making the user root, but every process running as
that user in an active local session can invoke the helper without
authentication and influence host VPN configuration. Only use this setting
on a trusted personal machine. The installed helper must remain root-owned,
not writable by ordinary users. The optional rule does not grant generic
sudo, shell or systemctl commands.

## Refined compact dashboard

The current desktop home retains its familiar circular connect control and
adds a glanceable receive/send/session-time row, keyboard-accessible rounded
server selector, quiet mode shortcut and softly rounded navigation chips.
The navy/ocean/mint visual language matches Android 0.7 without mirroring its
mobile layout. Profiles, root helper, systemd, Full/Split and DNS behavior are
unchanged. Launch from `python3 gui/nethervpn.py` after running the installer.

## Wide desktop layout

Linux uses a 930×720 desktop window with dedicated sidebar navigation,
center connection dial, visible tunnel mode, real interface traffic counters,
profile selector and diagnostics. It intentionally no longer looks like a
stretched mobile client. The old polkit/systemd helper and profile storage
are unchanged. The sidebar status reflects service state, not proof of full
end-to-end connectivity.

## Qt 6 desktop replacement

The default Linux UI has moved from the legacy Tkinter implementation to
`gui/nethervpn_qt.py` (PySide6). This is a UI-only migration: systemd,
`/usr/local/libexec/nether-desktop-helper`, portable `.conf` files, local
`.prefs`, routing and the Noise protocol are unchanged.

Run:

```bash
cd ~/NetherVPN
git pull --ff-only
sudo apt install python3-venv
bash scripts/install-desktop.sh
./.venv/bin/python gui/nethervpn_qt.py
```

The installer sets up an isolated `.venv` for Qt and points the desktop
launcher at the new UI. The old `gui/nethervpn.py` remains only for rollback,
not as the installed launcher. No sudo should be used to run either GUI.
Qt includes a resizable desktop layout, rounded navigation, animated
connection dial and separate pages for profiles, statistics and settings.

Test on a desktop with:
```bash
QT_QPA_PLATFORM=offscreen ./.venv/bin/python -m unittest discover -s tests -p test_gui_smoke.py -v
```

The circle indicates systemd and TUN interface state, not proof of encrypted
end-to-end traffic. For full tunnel verification, use the diagnostics and
external IPv4/DNS checks.
