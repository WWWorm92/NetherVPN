# NetherVPN for Android — 0.2.0 alpha

**Experimental functional Android VpnService**, with NetherVPN's own Noise IK UDP protocol.
This is NOT WireGuard and not a production-safe VPN.

## Capabilities
- Android 8.0+ (API 26), Android's system VPN consent dialog and foreground notification
- Import/paste the Linux client \`.conf\` profile, validate it, and save encrypted using AES-256-GCM and Android Keystore
- Authenticated Noise_IK_25519_ChaChaPoly_BLAKE2s handshake with pinned server static public key
- Full compatibility with NetherVPN v1 UDP framing: 0xA1 INIT, 0xA2 RESPONSE, DATA header 20 bytes
- 64-packet post-authentication replay protection, keepalive ping/pong, reconnect
- Protected UDP socket (no VPN recursion), native Android TUN read/write, network byte counters
- Explicit connect and disconnect; no root permissions

## Important limitations
- **Split tunnel only**: the route is the specific tunnel IP in \`Peer.AllowedIPs\` (/32). Ordinary Internet traffic is not tunneled or protected. DNS uses the default network. No IPv6 tunnel.
- A current Linux NetherVPN server must have its corresponding client X25519 public key authorized and configured, and a working TUN interface.
- The current server prototype cannot safely provide whole-device internet VPN (no NAT, rekey, secure DNS policy, robust multi-client handling, third-party security audit). Do not expose publicly or rely on it to protect sensitive traffic.
- No Android hardware or real server interoperability validation yet. The JVM cryptographic regression test checks a responder/initiator exchange, replay and AEAD.
- A debug APK is signed only with the build's ephemeral debug key. Subsequent GitHub Actions builds may not install as an update: uninstall the previous debug build first (export your profile separately).

## Build APK
GitHub Actions workflow **Android APK** builds on push/PR and uploads \`NetherVPN-Android-debug\` as an artifact.
Open the artifact ZIP from the successful workflow run and extract \`app-debug.apk\`.
Alternatively, open \`android/\` in Android Studio (JDK 17; Android SDK 35), then Build > Build APK(s).

## Profile example
\`\`\`ini
[Interface]
PrivateKey = <32-byte-client-private-key-as-64-hex-digits>
Address = 10.77.0.2/24
MTU = 1280

[Peer]
PublicKey = <32-byte-server-public-key-as-64-hex-digits>
Endpoint = my-server.example:51830
AllowedIPs = 10.77.0.1
\`\`\`

Keep the private key secret. Android will not display it again unless you choose “Edit saved profile”.
