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
