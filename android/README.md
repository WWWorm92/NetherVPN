# NetherVPN Android — 0.6 Alpha

**Experimental / unaudited VPN.** This is an Android VpnService implementation
of the NetherVPN custom Noise IK protocol. Android 8+ (API 26+) is required.

The compact 0.6 interface follows the same graphite-and-amber visual language
as the Linux desktop. The **single circular control** on the home screen
connects/disconnects the VPN, with status and compact traffic information below.
Tap the **route row** to change Full/Split and DNS, tap the **server card** to
import/replace/delete a profile, or use the bottom Home/Settings/Import bar.
Tap the traffic counters to see full statistics and packet-drop diagnostics.

Currently Android keeps **one active encrypted profile** in Android Keystore;
the server selector is an import/replace manager, not yet a multi-profile
collection. Import a separate unique .conf for each phone, rather than reusing
Linux credentials.

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

To connect: tap **＋ Импорт** to load the .conf. Tap **Настройки** to select
**Весь IPv4 через VPN** or **Только VPN-сервер**, enter an IPv4 DNS address
(e.g. `1.1.1.1`) and save. Then tap the **large circle** to connect.
Tap it again to disconnect. Settings changes take effect upon reconnecting.

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
checkboxes.** Open **Настройки → Постоянная VPN** from the client, select
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

## 0.7 Alpha dashboard refresh

The home screen uses a vertically scrollable connection summary with a
single wide connect/disconnect button, visible upload/download statistics,
selectable server profile and local routing mode. Detailed counters remain in
Statistics. Dark ocean/slate surfaces, mint connection accents, and matching
adaptive icons are shared with Linux. No changes to the Noise wire protocol,
profile keys or server authorization are made by this UI release.

Use a **unique server-provisioned .conf for each device**: running Linux and
Android simultaneously with one profile/key/IP replaces the peer session and
causes apparent reconnect loops. Create a separate Android identity with
`sudo nether-admin` before testing. This alpha still requires real-device
interoperability, full-tunnel NAT and DNS testing.

## 0.8 Alpha approved layout

The home screen now follows the selected NetherVPN visual direction: a
status-driven circular connection control, live counters, a selectable server,
local Full/Split routing shortcut, and fixed bottom navigation. The bottom
items invoke actual profile/settings actions; country flags and latency are
not invented because the protocol does not yet provide such data. The new
screen does not alter VPN sessions, keys, server permissions or NAT.

## Android 0.9 Alpha — Jetpack Compose / Material 3

The main Activity is now written with **Jetpack Compose**, Kotlin 2.0 Compose
compiler plugin and Material 3. It uses an animated connection ring with
phase-dependent color, soft navy surfaces, a fixed four-tab bottom bar and
dedicated Server, Profile and Settings screens. Status, counters and uptime
are read from the existing `NetherVpnService` preferences; this is not a
fake connection indicator. Because the backend currently stores one identity,
the Profiles screen explicitly shows one profile rather than pretending to
support a profile list. Country flags and ping are intentionally omitted
until real measurements exist.

**Important:** the Java VPN transport, Noise handshake, packet policy,
Keystore profile storage and server configuration have not been changed by
this UI migration. Installing this build does not resolve a shared-identity
session conflict or prove tunnel connectivity on a physical device.
Create a **different** client profile for Linux and Android with
`sudo nether-admin` before testing both simultaneously.

Android source build requires Java 17, SDK 35 and Gradle 8.9:

```bash
cd android
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The workflow `.github/workflows/android.yml` builds and publishes debug-signed
test APKs from `main`. Do not uninstall an existing debug-signed application
without securely backing up its unique client profile first, since signing
certificates can change between CI builds.

## Android 0.9.2 Alpha: connection stage diagnostics

After a failed connection open **Настройки → Диагностика** and tap the
diagnostics card. It copies only the connection phase, transport stage,
last error and aggregate counters — **never private keys or raw .conf files**.

Stages identify where the connection stops:
- `profile`: importing/decrypting/verifying the per-device configuration;
- `endpoint`: resolving the server address;
- `udp`: opening and protecting the socket;
- `noise_ik`: authenticated Noise IK handshake (server reachable / key accepted);
- `encrypted_ping`: first encrypted UDP PING/PONG and cipher compatibility;
- `tun`: creating Android VpnService TUN / routing;
- `connected`: encrypted exchange and TUN established (not proof of Internet egress);
- `retry`: reconnecting after failure.

On a USB-connected phone with adb, collect logcat while tapping Connect:

```bash
adb logcat -c
adb logcat -v time -s NetherVPN:I AndroidRuntime:E
```

On the **gateway**, in another terminal:

```bash
sudo journalctl -u nethervpn-server -f --no-pager
```

If the server reports `authenticated client 10.77.0.x`, Noise IK accepted
that identity. A rejection warning suggests a key/config/protocol mismatch.
No packets in the server journal is not proof of reachability failure:
capture UDP with tcpdump or check the server's listen interface/port.
The server logging changes require a **new server build and restart**;
a source-code update alone does not change the running service.

VPN debug report and Android/server log excerpts can identify the failing
step without sharing secrets. Do not paste the contents of a .conf file or
a private key into issue reports.
