# NetherVPN Android — 0.5 Alpha

**Experimental / unaudited VPN.** This is an Android VpnService implementation
of the NetherVPN custom Noise IK protocol. Android 8+ (API 26+) is required.

The new 0.5 interface follows the same dark navy/mint style as the Linux
desktop. It has a prominent connection status and button, session traffic
counters, discarded packet counter, elapsed time, Full/Split mode, local DNS,
secure profile import and an Android system VPN settings shortcut.

## Install

The latest **debug-signed** build is published by GitHub Actions as a
pre-release: https://github.com/WWWorm92/NetherVPN/releases

A debug signing certificate may change between builds. If Android reports
an incompatible signature, back up your unique client `.conf` securely before
uninstalling the older app; removing the app erases its encrypted profile.

## One universal configuration on both platforms

On the server:

```bash
sudo nether-admin
```

Choose create client, type a unique name and the publicly reachable
`IPv4:UDP-port`. Import the generated file
`/etc/nethervpn/clients/NAME.conf` into Android or Linux (use a different
client identity/key for each device):

```ini
[Interface]
Name = nvpn0
PrivateKey = <unique client private key>
Address = 10.77.0.3/32
MTU = 1280

[Peer]
PublicKey = <server public key>
Endpoint = 77.221.132.76:51830
AllowedIPs = 10.77.0.1
```

No platform flag, DNS, or Full/Split setting is required in this file. Routing
mode and DNS are private per-device preferences that do not modify the server.

To connect: import `.conf` with **＋ Импортировать**, select **Весь IPv4 через
VPN** or **Только VPN-сервер**, choose an IPv4 DNS address (e.g.
`1.1.1.1`), tap **Сохранить настройки**, then **Подключить**. Mode and DNS
changes are applied when the session reconnects.

## Linux parity and packet safety

Android now uses a separately tested, pure-Java `PacketPolicy`:

- **Outbound:** accept only well-formed IPv4 whose source is the assigned
  client VPN address. All other packets, including IPv6 and spoofed packets,
  are discarded **without disconnecting the authenticated Noise session**.
- **Inbound:** accept only well-formed IPv4 addressed to the assigned client.
  In Split mode, only the server tunnel address may be the source; Full mode
  permits return traffic from any source through the **authenticated tunnel**.
- Discarded packets are counted in the UI. Diagnostic logs report
  a reason and aggregate count, never raw packet contents or private keys.
- Genuine UDP/TUN I/O errors, failed handshake or peer keepalive timeout can
  still trigger reconnection, with a visible state and bounded retry delay.
- The connection panel reflects the service's own lifecycle phase, not just
  whether Android displays a VPN icon. Authenticated keepalive replies refresh
  a heartbeat; stale status (more than 30 seconds without an update) is shown
  as unknown rather than a green "connected" indication.

The gateway still needs **IPv4 forwarding, NAT and corresponding firewall
rules** for Full mode: [gateway guide](../docs/FULL-TUNNEL.md). There is no
native IPv6 egress yet; in Full Tunnel an IPv6 default route is intercepted and
IPv6 packets intentionally dropped.

**Always-on and block-without-VPN are Android OS policies, not application
checkboxes.** Open **Настройки VPN в Android** from the dashboard, select
NetherVPN and enable both options if desired. Without OS lockdown, apps may
use the physical network during disconnects/reconnects.

## Verify on a real phone

After connecting in Full Tunnel, compare the phone's external IPv4 to the
gateway's external IPv4; inspect gateway and Android logs for handshake,
keepalive, DNS and Internet return traffic. Test both Wi-Fi and mobile data,
network switches, service restarts, IPv6 and offline periods. Linux tests and
successful Android CI/JVM packet tests are **not** proof of real-device
interoperability or leak freedom.

Neither a production security audit nor reliable IPv6 forwarding, roaming,
kill switch implementation across all Android device vendor variations,
independent gateway/Android interoperability testing, and production key
rotation have been completed.
