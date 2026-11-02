# NetherVPN for Android — 0.2 alpha

This is an **experimental single-peer VPN client**, using Android VpnService,
[Noise-Java](https://github.com/signalapp/noise-java) (MIT), NetherVPN Noise IK,
UDP A1/A2 handshake, authenticated DATA v1 datagrams, a 64-packet replay window,
authenticated keepalives, reconnect, and Keystore-encrypted profile storage.

## Build and download APK

1. Open [Android CI runs](https://github.com/WWWorm92/NetherVPN/actions/workflows/android.yml).
2. Select a successful run and download the `NetherVPN-Android-alpha-APK` artifact.
3. Unzip it and install `app-debug.apk` on Android 8+.
4. If a main-branch build publishes a test release, use
   [GitHub Releases](https://github.com/WWWorm92/NetherVPN/releases) instead.

For local build: open the `android/` directory in Android Studio with JDK 17
and Android SDK 35, or run `gradle :app:assembleDebug` in `android/`.

## Configure

Import the generated NetherVPN **client.conf** (not a WireGuard profile):

```ini
[Interface]
Name = nvpn0
PrivateKey = YOUR_64_CHAR_X25519_CLIENT_PRIVATE_HEX
Address = 10.77.0.2/30
MTU = 1280

[Peer]
PublicKey = YOUR_64_CHAR_X25519_SERVER_PUBLIC_HEX
Endpoint = your.server.example:51830
AllowedIPs = 10.77.0.1
```

Ensure the server config explicitly authorizes the matching client static public
key. Android asks for VPN permission the first time.

The server currently has **no NAT / default-route / DNS full-tunnel support**.
The client deliberately routes only `Peer.AllowedIPs` (a single peer IP) through
TUN. Reaching `10.77.0.1` is the first interoperability test, **not** proof
of a general internet VPN.

## Limitations and security

Pre-alpha; **not audited and not suitable for privacy-critical traffic**.
One profile / one peer only, no multi-client server routing, no IPv6 leak
protection, no persistent always-on policy, no automatic key rotation, no
formal cross-language interoperability tests yet. Debug APK is debug-signed,
not a production release. Keystore protects stored secrets against simple
app-private file disclosure, not a compromised or rooted phone.

If the app reports `No encrypted PONG`, confirm the Go server uses the latest
keepalive implementation and that UDP port and authorized client public key
match. If TUN fails on Android, check for another active VPN.

Inspect status in the app and Android foreground notification. No private keys
are written to logs by this implementation.
