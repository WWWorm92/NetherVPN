# NetherVPN Android — diagnostic prototype

This is an Android Studio project **not a working VPN client**. It offers a dark diagnostic UI, endpoint persistence and DNS check. A UDP port check cannot prove UDP reachability and is deliberately not shown as connected. Do not paste private keys: prototype strips any PrivateKey lines when saving.

Open the `android/` directory in Android Studio with JDK 17, sync Gradle, and run app on Android 8+.

## Before a real tunnel
Implement Android `VpnService`, obtain user consent with `VpnService.prepare`, create the TUN interface through `Builder.establish()`, then port authenticated Noise IK exchange, packet framing, replay protection, keepalives, server pinning and bidirectional IP packet flow. Use `protect(socket)` for outer UDP socket so VPN traffic does not loop back into itself. Handle lifecycle, IPv6/DNS/route rollback and keys securely. Do not label the status "connected" until encrypted traffic is verified.

No APK was built in this environment; Android SDK/Gradle tooling was not available for validation.
