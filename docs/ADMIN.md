# NetherVPN server — interactive client management

Run **one command** on the server:

```bash
sudo nether-admin
```

The wizard asks for the device name, platform (Android or Linux), full-vs-split
IPv4 routing, the public IPv4/UDP server endpoint, and DNS for full tunnel.
If the outgoing network IP is public, it is offered as a default; on a NATed
host you must supply the public NAT address manually. At the end it shows the
choices, asks before creating files, and asks separately whether to restart
the service to activate the new client.

The wizard never prints private keys. The generated profile is saved at
`/etc/nethervpn/clients/<name>.conf` with permissions 0600; its public
identity is registered in `/etc/nethervpn/peers/<name>.conf`. The server keeps
only public keys in its registry.

The same profile works on Android 0.3+ and Linux full-tunnel client, with
`FullTunnel = true|false` and numeric IPv4 `DNS`. Use distinct profiles per
device.

Non-interactive server management remains possible:

```bash
sudo nether-admin create -name android -type android -mode full -endpoint 77.221.132.76:51830
sudo nether-admin create -name laptop -type linux -mode full -endpoint 77.221.132.76:51830 -dns 9.9.9.9
sudo nether-admin list
sudo nether-admin revoke -name android
sudo systemctl restart nethervpn-server
```

A full-tunnel server also needs IPv4 forwarding/NAT. See
[Full-tunnel server setup](FULL-TUNNEL.md). Do not send private client
profiles to chats, GitHub, issue trackers or public cloud folders. Share via
an encrypted channel, and remove stale exports when no longer needed.

Important: the service must be restarted to add/revoke peers, temporarily
interrupting existing encrypted sessions. The wizard asks before doing so.
