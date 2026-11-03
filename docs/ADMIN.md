# NetherVPN — server-side universal client manager

Run one command as root:

```bash
sudo nether-admin
```

A Russian terminal menu offers:
1. Create a client.
2. List authorized clients.
3. Revoke a client.
4. Display server information without private keys.
5. Exit.

Creating a client asks for **only** the device name and publicly reachable
numeric IPv4/UDP endpoint. The public address is suggested from the server's
bind/outbound address when possible (verify when behind NAT or using multiple
IP addresses). The manager generates X25519 keys, assigns the first free tunnel
IPv4, writes the 0600 secret-bearing profile to
`/etc/nethervpn/clients/NAME.conf`, and registers only the public key in
`/etc/nethervpn/peers/NAME.conf`. Finally, it asks whether to restart the
server to activate or revoke a client (active sessions briefly disconnect).

**No Android/Linux client types. No Full/Split Tunnel or DNS in server profiles.**
A universal `.conf` can be imported into either app. Each app stores and
applies traffic and DNS options **locally**, without requiring new keys.

The generated profile uses this format:

```ini
[Interface]
Name = nvpn0
PrivateKey = <unique 64-character private key>
Address = 10.77.0.3/32
MTU = 1280

[Peer]
PublicKey = <server public key>
Endpoint = 77.221.132.76:51830
AllowedIPs = 10.77.0.1
```

Non-interactive administration:

```bash
sudo nether-admin create -name laptop -endpoint 77.221.132.76:51830
sudo nether-admin list
sudo nether-admin revoke -name laptop
sudo systemctl restart nethervpn-server
```

Store and transfer private profiles safely. Do not post a private key to
GitHub/issues/chat or reuse the same profile simultaneously on different
devices. The server needs explicit NAT/IPv4 forwarding for Full Tunnel
([server guide](FULL-TUNNEL.md)).
