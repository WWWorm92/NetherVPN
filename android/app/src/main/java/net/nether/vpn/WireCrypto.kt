package net.nether.vpn

import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** NetherVPN v1 on-wire compatibility with flynn/noise Noise_IK_25519_ChaChaPoly_BLAKE2s. */
internal object WireCrypto {
    private val random = SecureRandom()

    fun hash(input: ByteArray): ByteArray {
        val digest = Blake2sDigest(256)
        digest.update(input, 0, input.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val h = HMac(Blake2sDigest(256))
        h.init(KeyParameter(key))
        h.update(data, 0, data.size)
        return ByteArray(32).also { h.doFinal(it, 0) }
    }

    fun hkdf(key: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(key, ikm)
        val out1 = hmac(temp, byteArrayOf(1))
        val out2 = hmac(temp, out1 + byteArrayOf(2))
        temp.fill(0)
        return out1 to out2
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        return ByteArray(32).also { X25519.generatePublicKey(privateKey, 0, it, 0) }
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && publicKey.size == 32)
        val secret = ByteArray(32)
        check(X25519.calculateAgreement(privateKey, 0, publicKey, 0, secret, 0)) {
            "Недопустимый публичный ключ X25519"
        }
        return secret
    }

    fun newPrivateKey(): ByteArray = ByteArray(32).also { X25519.generatePrivateKey(random, it) }

    fun chacha(key: ByteArray, sequence: Long, aad: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0..7) nonce[i + 4] = ((sequence ushr (8 * i)) and 255L).toByte()
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(data.size))
        var n = cipher.processBytes(data, 0, data.size, out, 0)
        n += cipher.doFinal(out, n)
        return out.copyOf(n)
    }

    fun isIpPacket(packet: ByteArray, size: Int = packet.size): Boolean {
        if (size < 20) return false
        return when ((packet[0].toInt() and 255) ushr 4) {
            4 -> {
                val headerLen = (packet[0].toInt() and 15) * 4
                val len = ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
                headerLen >= 20 && headerLen <= size && len == size
            }
            6 -> {
                if (size < 40) return false
                val len = ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)
                size == len + 40
            }
            else -> false
        }
    }

    fun sourceAddress(packet: ByteArray): ByteArray? = when ((packet[0].toInt() and 255) ushr 4) {
        4 -> if (packet.size >= 20) packet.copyOfRange(12, 16) else null
        6 -> if (packet.size >= 40) packet.copyOfRange(8, 24) else null
        else -> null
    }
}

internal class NoiseInitiator(privateKey: ByteArray, private val serverPublic: ByteArray) {
    private val staticPrivate = privateKey.clone()
    private val staticPublic = WireCrypto.publicKey(staticPrivate)
    private val ephemeralPrivate = WireCrypto.newPrivateKey()
    private var h = WireCrypto.hash("Noise_IK_25519_ChaChaPoly_BLAKE2s".toByteArray(Charsets.UTF_8))
    private var ck = h.clone()
    private var key: ByteArray? = null
    private var nonce = 0L
    private var started = false

    init {
        require(serverPublic.size == 32)
        mixHash("NetherVPN/noise-ik/v1".toByteArray(Charsets.UTF_8))
        mixHash(serverPublic)
    }

    private fun mixHash(data: ByteArray) { h = WireCrypto.hash(h + data) }

    private fun mixKey(ikm: ByteArray) {
        val (newCk, newKey) = WireCrypto.hkdf(ck, ikm)
        ck.fill(0)
        ck = newCk
        key?.fill(0)
        key = newKey
        ikm.fill(0)
        nonce = 0L
    }

    private fun encryptHash(data: ByteArray): ByteArray {
        val encrypted = key?.let { WireCrypto.chacha(it, nonce++, h, data, true) } ?: data
        mixHash(encrypted)
        return encrypted
    }

    private fun decryptHash(data: ByteArray): ByteArray {
        val plain = key?.let { WireCrypto.chacha(it, nonce++, h, data, false) } ?: data
        mixHash(data)
        return plain
    }

    fun start(): ByteArray {
        check(!started) { "Handshake already started" }
        started = true
        val e = WireCrypto.publicKey(ephemeralPrivate)
        mixHash(e)
        mixKey(WireCrypto.dh(ephemeralPrivate, serverPublic)) // es
        val encryptedStatic = encryptHash(staticPublic)
        mixKey(WireCrypto.dh(staticPrivate, serverPublic)) // ss
        return byteArrayOf(0xA1.toByte()) + e + encryptedStatic + encryptHash(ByteArray(0))
    }

    fun finish(packet: ByteArray): DatagramSession {
        check(started && packet.size >= 1 + 32 + 16 && packet[0] == 0xA2.toByte()) {
            "Некорректный ответ сервера"
        }
        val ephemeralServer = packet.copyOfRange(1, 33)
        mixHash(ephemeralServer)
        mixKey(WireCrypto.dh(ephemeralPrivate, ephemeralServer)) // ee
        mixKey(WireCrypto.dh(staticPrivate, ephemeralServer)) // se
        val params = decryptHash(packet.copyOfRange(33, packet.size))
        require(params.size == 10) { "Неожиданные параметры VPN-сессии" }
        val bb = ByteBuffer.wrap(params).order(ByteOrder.BIG_ENDIAN)
        val connectionId = bb.long
        val epoch = bb.short.toInt() and 0xffff
        require(connectionId != 0L && epoch > 0)
        val (send, receive) = WireCrypto.hkdf(ck, ByteArray(0)) // Noise split
        staticPrivate.fill(0)
        ephemeralPrivate.fill(0)
        key?.fill(0)
        ck.fill(0)
        return DatagramSession(send, receive, connectionId, epoch)
    }
}

internal class DatagramSession(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val connectionId: Long,
    private val epoch: Int
) {
    private var counter = 0L
    private var maxReceived = 0L
    private var replayBits = 0L
    private var initialized = false

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        require(counter < Long.MAX_VALUE - 2) { "Требуется обновить VPN-ключи" }
        val header = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(3).putLong(connectionId).putShort(epoch.toShort()).putLong(counter).array()
        val result = header + WireCrypto.chacha(sendKey, counter, header, plain, true)
        counter++
        return result
    }

    @Synchronized
    fun open(packet: ByteArray): ByteArray {
        require(packet.size >= 36 && packet[0] == 1.toByte() && packet[1] == 3.toByte())
        val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        b.position(2)
        require(b.long == connectionId && (b.short.toInt() and 0xffff) == epoch)
        val seq = b.long
        require(seq >= 0)
        val plain = WireCrypto.chacha(receiveKey, seq, packet.copyOfRange(0, 20),
            packet.copyOfRange(20, packet.size), false)
        if (!initialized) {
            initialized = true
            maxReceived = seq
            replayBits = 1L
        } else if (seq > maxReceived) {
            val delta = seq - maxReceived
            replayBits = if (delta >= 64) 1 else (replayBits shl delta.toInt()) or 1L
            maxReceived = seq
        } else {
            val age = maxReceived - seq
            require(age < 64 && replayBits and (1L shl age.toInt()) == 0L) { "Повтор пакета" }
            replayBits = replayBits or (1L shl age.toInt())
        }
        return plain
    }
}
