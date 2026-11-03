# NetherVPN server and Android deployment

The current Linux server supports multiple authenticated clients via a root-owned
public-key registry. The Android 0.3-alpha app supports opt-in full-tunnel **IPv4**
with DNS through the VPN; IPv6 is intercepted and intentionally dropped, not
routed to the Internet. The Linux desktop client supports both split tunnel and full IPv4 tunnel
using the same profile format as Android. See [Linux desktop](DESKTOP.md).

For complete installation, NAT persistence, client creation and rollback use
[Full-tunnel setup](FULL-TUNNEL.md).

Quick server install (Ubuntu/Debian):

```bash
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-server.sh
sudo systemctl restart nethervpn-server
```

For the example private tunnel subnet, change the gateway's Interface.Address
to `10.77.0.1/24` before generating second and subsequent clients.

Generate root-only `.conf` profiles via `nether-admin`:

```bash
sudo nether-admin create -name phone -endpoint 45.151.69.184:51830
sudo systemctl restart nethervpn-server
```

Enable NAT separately (never change host default route):

```bash
sudo nethervpn-full-tunnel enable eth0 10.77.0.0/24 nvpn0
sudo systemctl enable --now nethervpn-full-tunnel.service
```

Use your real WAN interface in place of `eth0`. If the server uses another
subnet, adjust the CIDR and confirm it does not overlap local networks.
Watch the server with `sudo journalctl -u nethervpn-server -f`.

Do **not** use the experimental software for security-critical traffic.
VPN account revocation requires a service restart; rate limiting, formal
anti-DoS protections, rekey, Android real-device interoperability and auditing
remain open work. Use Android's *Block connections without VPN* option to
avoid direct traffic while disconnected.
