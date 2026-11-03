# NetherVPN Linux desktop — full IPv4 tunnel (experimental)

The Linux client supports **FullTunnel = true**, DNS via systemd-resolved,
IPv4 policy routing and IPv6 fail-closed blocking, with restoration on
graceful disconnect. The GUI is `gui/nethervpn.py`; the systemd service runs
the privileged Go client and owns the TUN and routes.

**Warning:** Full Tunnel changes routing for the **entire workstation**,
including remote SSH sessions, LAN printers and local services. Do not
activate from a remote session unless you have console access. The custom
VPN has not undergone an independent security audit.

## Install on Linux workstation

```bash
sudo apt install python3-tk polkitd pkexec iproute2 systemd-resolved
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-desktop.sh
python3 gui/nethervpn.py
```

Import a unique `.conf` created on the gateway using `sudo nether-admin`.
Choose Linux and **Full Tunnel** in the wizard. The GUI displays the chosen
routing mode and DNS, asks before activating Full Tunnel, and uses polkit
to copy the profile to `/etc/nethervpn/client.conf` as a 0600 root-owned
file. Closing the window leaves the service running; disconnect via the
GUI or `sudo systemctl stop nethervpn-client`.

## Linux Full Tunnel implementation

Before connecting, the client validates keys and the numeric IPv4 endpoint,
then creates a dedicated Linux policy routing table **51830**:

- Rule priority **12100** routes only the pinned public VPN server endpoint
  via the host's pre-existing `main` routing table, preventing a UDP loop.
- Rule priority **12101** sends normal IPv4 and IPv6 through dedicated table
  51830. This table initially has only **unreachable** defaults.
- When the encrypted handshake succeeds and TUN is ready, a lower-metric
  `default dev nvpn0` activates IPv4 forwarding inside that table.
- DNS is configured on `nvpn0` through `resolvectl dns`,
  `resolvectl domain nvpn0 '~.'` and `resolvectl default-route`.
- Replies from the Internet may originate at any address but must target
  the authenticated client's assigned VPN IP.
- IPv6 remains unreachable while Full Tunnel is active; native IPv6
  tunneling has not been implemented yet.

On TUN loss, the tunnel route is removed, but unreachable routes stay active
while the VPN reconnects. **On graceful disconnect**, routing rules and
NetherVPN DNS overrides are removed. The main default route is never replaced.

**FullTunnel = false** keeps the machine's ordinary default routes and DNS,
adding only the inside server-IP route via the TUN. Generated /32 profiles
work in both modes.

## Troubleshooting and manual recovery

```bash
systemctl status nethervpn-client --no-pager -l
sudo journalctl -u nethervpn-client -n 80 --no-pager
ip -4 rule show
ip -4 route show table 51830
ip -6 route show table 51830
resolvectl status nvpn0
curl -4 https://api.ipify.org
```

The public IPv4 should match your gateway when Full Tunnel is active.

On force-kill (SIGKILL) the fail-closed policy may remain after the process
exits, preventing direct Internet. First try stopping the service gracefully.
If it is already dead and you have **verified** rule priorities 12100/12101
and table 51830 belong to NetherVPN, an administrator can remove them:

```bash
sudo ip -4 rule del pref 12100
sudo ip -4 rule del pref 12101
sudo ip -6 rule del pref 12101
sudo ip -4 route flush table 51830
sudo ip -6 route flush table 51830
```

**Do not run those commands blindly** on machines with other policy routing.
The controller also refuses to start if its reserved policy table or rules
are already occupied.

This is a reconnect-time fail-closed policy, **not** a certified always-on
kill switch across every systemd service restart or administrative stop.
A systemd-resolved-compatible setup is required: if `resolvectl` is missing,
the client refuses Full Tunnel rather than silently using regular DNS.
No real-device full-tunnel integration or DNS/IPv6 leak audit is claimed.
