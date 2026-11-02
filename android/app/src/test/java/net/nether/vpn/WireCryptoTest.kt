package net.nether.vpn

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class WireCryptoTest {
    @Test fun ikSessionAndReplay() {
        val serverPrivate = WireCrypto.newPrivateKey()
        val serverPublic = WireCrypto.publicKey(serverPrivate)
        val clientPrivate = WireCrypto.newPrivateKey()
        val clientPublic = WireCrypto.publicKey(clientPrivate)
        val client = NoiseInitiator(clientPrivate, serverPublic)
        val request = client.start()
        assertEquals(97, request.size)
        assertEquals(0xA1.toByte(), request[0])

        // Independent responder-side state progression following Noise IK.
        var h = WireCrypto.hash("Noise_IK_25519_ChaChaPoly_BLAKE2s".toByteArray())
        var ck = h.clone()
        fun mixHash(data: ByteArray) { h = WireCrypto.hash(h + data) }
        fun mixKey(ikm: ByteArray): ByteArray {
            val result = WireCrypto.hkdf(ck, ikm)
            ck = result.first
            return result.second
        }
        mixHash("NetherVPN/noise-ik/v1".toByteArray())
        mixHash(serverPublic)
        val clientE = request.copyOfRange(1, 33)
        mixHash(clientE)
        var k = mixKey(WireCrypto.dh(serverPrivate, clientE))
        val encryptedStatic = request.copyOfRange(33, 81)
        val peerStatic = WireCrypto.chacha(k, 0, h, encryptedStatic, false)
        assertArrayEquals(clientPublic, peerStatic)
        mixHash(encryptedStatic)
        k = mixKey(WireCrypto.dh(serverPrivate, peerStatic))
        val endTag = request.copyOfRange(81, 97)
        assertEquals(0, WireCrypto.chacha(k, 0, h, endTag, false).size)
        mixHash(endTag)

        val serverEphemeralPrivate = WireCrypto.newPrivateKey()
        val serverE = WireCrypto.publicKey(serverEphemeralPrivate)
        mixHash(serverE)
        mixKey(WireCrypto.dh(serverEphemeralPrivate, clientE))
        k = mixKey(WireCrypto.dh(serverEphemeralPrivate, clientPublic))
        val params = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            .putLong(123456789L).putShort(1).array()
        val encryptedParams = WireCrypto.chacha(k, 0, h, params, true)
        mixHash(encryptedParams)
        val response = byteArrayOf(0xA2.toByte()) + serverE + encryptedParams
        val clientSession = client.finish(response)
        val (clientToServer, serverToClient) = WireCrypto.hkdf(ck, ByteArray(0))
        val serverSession = DatagramSession(serverToClient, clientToServer, 123456789L, 1)

        val packet = clientSession.seal("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), serverSession.open(packet))
        try { serverSession.open(packet); fail("replayed packet accepted") }
        catch (_: IllegalArgumentException) { }
        val reply = serverSession.seal("world".toByteArray())
        assertArrayEquals("world".toByteArray(), clientSession.open(reply))
        val tampered = packet.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try { serverSession.open(tampered); fail("tampered packet accepted") }
        catch (_: Exception) { }
    }

    @Test fun packetLengths() {
        val good = ByteArray(20)
        good[0] = 0x45
        good[2] = 0
        good[3] = 20
        assertTrue(WireCrypto.isIpPacket(good))
        assertFalse(WireCrypto.isIpPacket(good + byteArrayOf(1)))
    }
}
