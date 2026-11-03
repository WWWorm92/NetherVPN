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

Click **Сохранить настройки** and then reconnect to apply. Routing preferences
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

To inspect:

```bash
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
