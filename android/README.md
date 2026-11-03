# NetherVPN Android — local Full/Split VPN settings (0.4 alpha)

Android 8+ experimental NetherVPN using VpnService + Noise IK. [GitHub Releases](https://github.com/WWWorm92/NetherVPN/releases)
publishes debug-signed APK builds (each CI build may have a different signing
certificate; uninstall/reinstall may be necessary, which deletes saved profiles).

## Universal config from server

Generate a unique profile with `sudo nether-admin` on the gateway and securely
transfer `/etc/nethervpn/clients/NAME.conf` to the phone. The server no longer
asks for the phone's OS or traffic-routing mode. It creates a universal profile:

```ini
[Interface]
Name = nvpn0
PrivateKey = <client private key>
Address = 10.77.0.3/32
MTU = 1280

[Peer]
PublicKey = <server public key>
Endpoint = 77.221.132.76:51830
AllowedIPs = 10.77.0.1
```

Import this file from the app, then set **Full IPv4** or **Split Tunnel** and
DNS on the app's main screen and tap **Save mode and DNS**. Preferences are
held separately in Android private SharedPreferences; the identity and keys
are stored encrypted by Android Keystore. Reconnect the VPN to apply changes.

- Full IPv4: default `0.0.0.0/0` via VPN, DNS using the chosen numeric IPv4
  resolver; IPv6 is intercepted but deliberately blocked.
- Split: only `Peer.AllowedIPs` (the server tunnel IP) is routed via VPN.
- Old profiles with `FullTunnel`/`DNS` fields are accepted for migration;
  once options are saved in the UI the app's saved values override them.
- Android Always-on VPN and **Block connections without VPN** are enforced by
  the OS, not by an app checkbox. Tap **Настройки Always-on VPN и блокировки
  утечек** to open Android VPN settings and enable both if desired.
  Without the OS block policy, disconnected/reconnecting traffic can escape.

The gateway must have IPv4 NAT and forwarding enabled for Full Tunnel.
The VPN does not support native IPv6 egress or Internet traffic of hotspot
tethered clients. Cryptographic and leak-safety audit and real-device tests
remain pending; **not a production privacy tool**.
