package net.nether.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.InetAddress
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class VpnProfile(
    val privateKey: ByteArray,
    val serverKey: ByteArray,
    val serverHost: String,
    val serverPort: Int,
    val address: String,
    val prefix: Int,
    val serverTunnelAddress: ByteArray,
    val dnsServer: String,
    val mtu: Int
) {
    companion object {
        fun parse(contents: String): VpnProfile {
            require(contents.length <= 64 * 1024) { "Слишком большой профиль" }
            var section = ""
            val values = mutableMapOf<String, String>()
            contents.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach
                if (line.startsWith('[') && line.endsWith(']')) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    return@forEach
                }
                val pieces = line.split('=', limit = 2)
                require(pieces.size == 2) { "Ожидается параметр key=value" }
                values[section + "." + pieces[0].trim().lowercase()] = pieces[1].trim()
            }
            fun get(key: String) = values[key]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("В профиле отсутствует $key")

            fun hex(value: String): ByteArray {
                require(value.length == 64 && value.all { it in "0123456789abcdefABCDEF" }) {
                    "Ключ должен содержать 64 шестнадцатеричных символа"
                }
                return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            val localKey = hex(get("interface.privatekey"))
            val remoteKey = hex(get("peer.publickey"))
            val endpoint = get("peer.endpoint")
            val portSep = endpoint.lastIndexOf(':')
            require(portSep > 0)
            val host = endpoint.substring(0, portSep).removePrefix("[").removeSuffix("]")
            val port = endpoint.substring(portSep + 1).toIntOrNull()
            require(host.isNotBlank() && port != null && port in 1..65535) { "Неправильный Endpoint" }
            val addressBits = get("interface.address").split('/', limit = 2)
            require(addressBits.size == 2) { "Address должен быть IPv4/CIDR" }
            val address = addressBits[0].trim()
            val prefix = addressBits[1].toIntOrNull()
            require(prefix != null && prefix in 1..32 && isIpv4Literal(address)) { "Неправильный Address" }
            val peerAddress = get("peer.allowedips")
            require(isIpv4Literal(peerAddress)) { "AllowedIPs должен быть одним IPv4-адресом сервера" }
            val mtu = values["interface.mtu"]?.toIntOrNull() ?: 1280
            require(mtu in 576..1500) { "MTU должен быть от 576 до 1500" }
            val dns = values["interface.dns"] ?: "1.1.1.1"
            require(isIpv4Literal(dns)) { "Неправильный DNS" }
            return VpnProfile(localKey, remoteKey, host, port, address, prefix,
                InetAddress.getByName(peerAddress).address, dns, mtu)
        }

        private fun isIpv4Literal(value: String): Boolean {
            val blocks = value.split('.')
            return blocks.size == 4 && blocks.all {
                it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull()?.let { n -> n in 0..255 } == true
            }
        }
    }
}

/** Profile is AES-256-GCM encrypted at rest using a non-exportable Android Keystore key. */
internal object ProfileStore {
    private const val ALIAS = "NetherVPN_Profile_v1"
    private const val PREFS = "nethervpn_state"

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun save(context: Context, contents: String) {
        VpnProfile.parse(contents) // validate before storing any material
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val output = cipher.iv + cipher.doFinal(contents.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("profile", Base64.encodeToString(output, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): String? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("profile", null) ?: return null
        val encoded = Base64.decode(value, Base64.DEFAULT)
        require(encoded.size >= 12 + 16) { "Сохранённый профиль повреждён" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
        return cipher.doFinal(encoded.copyOfRange(12, encoded.size)).toString(Charsets.UTF_8)
    }

    fun hasProfile(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .contains("profile")

    fun state(context: Context, status: String, rx: Long = 0L, tx: Long = 0L) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", status).putLong("rx", rx).putLong("tx", tx).apply()
    }

    fun status(context: Context): Triple<String, Long, Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getString("status", "Отключено") ?: "Отключено",
            p.getLong("rx", 0L), p.getLong("tx", 0L))
    }
}
