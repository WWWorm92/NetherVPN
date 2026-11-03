# NetherVPN deployment — universal identities

Install/update on the gateway:

```bash
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-server.sh
sudo systemctl restart nethervpn-server
sudo nether-admin
```

`nether-admin` now opens an interactive management menu with create, list,
revoke and server info. The created `.conf` has keys, endpoint and VPN
addresses **only**; it has no OS, DNS or Full/Split mode. Use a different
profile for each phone/laptop and configure the route mode locally in the app.

The example gateway uses `Address = 10.77.0.1/24` and legacy client at
`10.77.0.2`. The external UDP endpoint is the publicly reachable address
(not necessarily the source reported by `ip route get` when behind NAT).
The gateway must allow incoming UDP 51830.

For Full Tunnel clients, independently configure server IPv4 forwarding and
scoped MASQUERADE rules. See [NAT setup and rollback](FULL-TUNNEL.md).

The Linux workstation runs `bash scripts/install-desktop.sh` and imports
the same config into `gui/nethervpn.py`. Full/Split mode, DNS and boot
autoconnect are local GUI preferences. See [Linux desktop](DESKTOP.md).

Android settings live in the Android app; Always-on VPN and system-wide
block-without-VPN are configured in Android Settings.

The custom protocol is pre-alpha; no security review or end-to-end
leak-safety test is claimed.
