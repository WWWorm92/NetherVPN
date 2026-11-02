# NetherVPN Android — experimental full-tunnel IPv4

Android 8+ (API 26+) application implementing a foreground Android
`VpnService` and NetherVPN Noise IK encrypted datagrams.
[GitHub Releases](https://github.com/WWWorm92/NetherVPN/releases)
contains the latest debug-signed alpha APK (0.3-alpha). Since GitHub runners
generate a new **debug signing certificate** for each build, updating a
previous test APK may fail with a signature mismatch. Export/import your
profile securely and uninstall the old alpha APK if Android refuses the new
installation; removing the app erases its encrypted stored profile. Source
builds can be downloaded
from [Android CI](https://github.com/WWWorm92/NetherVPN/actions/workflows/android.yml).

## Provisioning (server)

Run the official root-only client manager:

```bash
sudo nether-admin create -name phone -endpoint 45.151.69.184:51830
sudo systemctl restart nethervpn-server
```

Copy the generated `/etc/nethervpn/clients/phone.conf` using a secure channel,
import it in the Android app and grant Android VPN consent. Each Android
device must have an independent identity and unique tunnel address.

A generated profile looks like:

```ini
[Interface]
Name = nvpn0
PrivateKey = PRIVATE_X25519_HEX_FROM_GENERATOR
Address = 10.77.0.3/32
MTU = 1280
FullTunnel = true
DNS = 1.1.1.1

[Peer]
PublicKey = PINNED_SERVER_PUBLIC_X25519_HEX
Endpoint = 45.151.69.184:51830
AllowedIPs = 10.77.0.1
```

`FullTunnel = true` sends all IPv4 and DNS through VPN. `AllowedIPs` here
identifies the remote tunnel endpoint (10.77.0.1), not the default-route
policy. With `FullTunnel = false`, only the peer IP is routed. The public
`Endpoint` is required to be a numeric IPv4 in full-tunnel mode to avoid
external DNS lookups during reconnect.

### Required gateway configuration

On the server, enable scoped NAT/forwarding **before** expecting full Internet:

```bash
sudo nethervpn-full-tunnel enable eth0 10.77.0.0/24 nvpn0
sudo systemctl enable --now nethervpn-full-tunnel.service
```

Replace `eth0` with the actual outgoing interface. Read
[full server setup, rollback and testing](../docs/FULL-TUNNEL.md).

### Leak protection and compatibility

- IPv6 default route is intercepted but IPv6 packets are **dropped** until
  IPv6 egress is implemented. This prevents native IPv6 bypass while connected
  but disables IPv6-only destinations.
- Encrypted UDP to the public gateway uses `VpnService.protect` and is
  excluded from the VPN to avoid recursion.
- DNS is configured inside the TUN using `Builder.addDnsServer`.
- For **fail-closed during disconnect**, turn on Android system Settings
  → VPN → NetherVPN → *Always-on VPN* and *Block connections without VPN*.
  Otherwise the OS can use normal internet when VPN is disconnected.
- The app does not automatically route hotspot/tethered clients.
- Debug APK, no independently verified real-device interoperability or security
  audit. **Do not use for sensitive traffic.**

Profile private keys are stored encrypted with Android Keystore. Do not paste
or commit private keys in chat/issues. Remove transferred export files where
appropriate.
