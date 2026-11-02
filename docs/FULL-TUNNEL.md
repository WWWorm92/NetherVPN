# NetherVPN experimental Android full-tunnel IPv4 setup

**Pre-alpha:** do not use for sensitive data. IPv6 is deliberately blocked inside
the Android tunnel until authenticated IPv6 routing/NAT and DNS leak tests exist.
An active encrypted Android VPN connection routes **all IPv4** apps via NetherVPN,
not merely the server's VPN IP. This is not WireGuard.

## 1. Prepare gateway and preserve existing credentials

Back up `/etc/nethervpn/server.conf` securely. Switch its interface subnet from
`Address = 10.77.0.1/30` to `Address = 10.77.0.1/24` **only if** the
server uses the example 10.77.0.x network and does not conflict with other
networks. The legacy authorized peer at `10.77.0.2` remains usable.
The service also reads additional public authorized clients from
`/etc/nethervpn/peers/*.conf`.

Install or update the binaries on the server:

```bash
cd ~/NetherVPN
git pull --ff-only
bash scripts/install-server.sh
sudo systemctl restart nethervpn-server
```

## 2. Enable NAT and scoped forwarding (administrator only)

Install `iptables`, `iproute2`, `python3`. Identify WAN interface:

```bash
ip -4 route get 1.1.1.1
```

Assuming the public outgoing interface is `eth0`:

```bash
sudo bash scripts/configure-full-tunnel.sh enable eth0 10.77.0.0/24 nvpn0
sudo bash scripts/configure-full-tunnel.sh status eth0 10.77.0.0/24 nvpn0
sudo systemctl enable --now nethervpn-full-tunnel.service
```

The script enables IPv4 forwarding and installs **only** scoped
iptables filter and MASQUERADE rules. It does not alter the host default route.
It stores a root-only `/etc/nethervpn/full-tunnel.env` for the optional
`nethervpn-full-tunnel.service`. Enabling that service reapplies the scoped
rules on boot. The persistent systemd service must be explicitly enabled; do
not rely on one-time iptables changes surviving a reboot.
If your host uses nftables, firewalld, UFW or cloud security-group restrictions,
verify that the VPN interface forwarding and inbound UDP port are allowed; an
ACCEPT in one firewall chain may not override another rejecting rule. The rules
are runtime-only; for persistence add a trusted boot-time systemd oneshot or
persist them using your host firewall manager.

Rollback:

```bash
sudo systemctl disable --now nethervpn-full-tunnel.service
sudo bash scripts/configure-full-tunnel.sh disable eth0 10.77.0.0/24 nvpn0
```

Do **not** casually disable `net.ipv4.ip_forward` if other routing services need it.

## 3. Create a unique Android profile

```bash
sudo nether-admin create -name phone -endpoint 45.151.69.184:51830
sudo nether-admin list
sudo systemctl restart nethervpn-server
```

The generated secret-bearing profile is at
`/etc/nethervpn/clients/phone.conf`, mode 0600, and can be transferred via
a secure channel. The public authorization record is stored separately in
`/etc/nethervpn/peers/phone.conf`.

For a second phone, create a different `-name`. The generator allocates the
next free VPN IPv4 and does **not** replace the existing Linux peer.

To revoke a device:

```bash
sudo nether-admin revoke -name phone
sudo systemctl restart nethervpn-server
```

For transfer to the logged-in administrator's home (do not paste secrets into chat):

```bash
sudo install -o "$(id -un)" -g "$(id -gn)" -m 600 /etc/nethervpn/clients/phone.conf "$HOME/phone.conf"
```

After transfer, delete unnecessary secret exports from the server if your
backup/provisioning policy permits it. **Never paste a private key into GitHub
issues, chat or logs.** Do not commit generated profiles.

## 4. Android client

Install the Android APK from GitHub Releases and import the profile. Example:

```ini
[Interface]
Name = nvpn0
PrivateKey = <generated-private-key>
Address = 10.77.0.3/32
MTU = 1280
FullTunnel = true
DNS = 1.1.1.1

[Peer]
PublicKey = <pinned-server-public-key>
Endpoint = 45.151.69.184:51830
AllowedIPs = 10.77.0.1
```

Android routes 0.0.0.0/0 to the TUN and configures DNS (1.1.1.1, entered in
the profile). DNS therefore travels through the encrypted IPv4 tunnel. IPv6
0::/0 is captured and intentionally dropped (no fallback through cellular/Wi-Fi).
The UDP socket to the public server is excluded using `VpnService.protect`.

For a **device-level fail-closed policy on disconnect**, enable Android Settings
→ Network & Internet → VPN → NetherVPN → *Always-on VPN* and
*Block connections without VPN*. Without Android's blocking policy, traffic
may use a direct connection while the VPN is disconnected or reconnecting.

Test from the phone, on mobile and Wi-Fi, including IPv4/DNS, then run
`sudo journalctl -u nethervpn-server -f` on the server. Confirm external IPv4
is the server's public IP. Test disconnection and check there is no DNS/IPv6
leak. Do not assume that a successful APK build proves tunnel operation.

## Current security constraints

No independently verified Android↔Go interoperability, no production key
rotation, no server DoS/rate-limit hardening, no automatic service reload,
no server-managed DNS resolver and no support for native IPv6 egress.
When using Android full-tunnel mode, you must configure NAT on the server.
