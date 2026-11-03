package net.nether.vpn;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import org.junit.Test;
import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback Noise IK, framed encrypted PING/PONG test with real UDP sockets. */
public final class NoiseTransportTest {
    private static String hex(byte[] key) {
        StringBuilder out = new StringBuilder();
        for (byte b : key) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    @Test public void authenticatedHandshakeAndEncryptedKeepalive() throws Exception {
        HandshakeState responder = new HandshakeState(
                "Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER);
        HandshakeState clientIdentity = new HandshakeState(
                "Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.INITIATOR);
        responder.getLocalKeyPair().generateKeyPair();
        clientIdentity.getLocalKeyPair().generateKeyPair();
        byte[] serverPub = new byte[32], clientPriv = new byte[32];
        responder.getLocalKeyPair().getPublicKey(serverPub, 0);
        clientIdentity.getLocalKeyPair().getPrivateKey(clientPriv, 0);
        byte[] prologue = "NetherVPN/noise-ik/v1".getBytes(StandardCharsets.US_ASCII);
        responder.setPrologue(prologue, 0, prologue.length);
        responder.start();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            String conf = "[Interface]\nPrivateKey = " + hex(clientPriv)
                    + "\nAddress = 10.77.0.5/32\nMTU = 1280"
                    + "\n[Peer]\nPublicKey = " + hex(serverPub)
                    + "\nEndpoint = 127.0.0.1:" + server.getLocalPort()
                    + "\nAllowedIPs = 10.77.0.1\n";
            VpnProfile profile = VpnProfile.parse(conf);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    server.setSoTimeout(5000);
                    byte[] incoming = new byte[4096];
                    DatagramPacket init = new DatagramPacket(incoming, incoming.length);
                    server.receive(init);
                    assertEquals((byte) 0xA1, incoming[0]);
                    responder.readMessage(incoming, 1, init.getLength()-1,
                            new byte[32], 0);
                    byte[] sessionId = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
                            .putLong(12345678L).putShort((short) 1).array();
                    byte[] response = new byte[256];
                    response[0] = (byte) 0xA2;
                    int responseLength = responder.writeMessage(
                            response, 1, sessionId, 0, sessionId.length);
                    server.send(new DatagramPacket(
                            response, responseLength + 1, init.getSocketAddress()));
                    CipherStatePair pair = responder.split();
                    DatagramPacket ping = new DatagramPacket(incoming, incoming.length);
                    server.receive(ping);
                    assertEquals(1, incoming[0]);
                    assertEquals(3, incoming[1]);
                    byte[] header = Arrays.copyOfRange(incoming, 0, 20);
                    long nonce = ByteBuffer.wrap(header, 12, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    CipherState recv = pair.getReceiver();
                    recv.setNonce(nonce);
                    byte[] plain = new byte[ping.getLength() - 36];
                    int n = recv.decryptWithAd(header, incoming, 20, plain, 0, ping.getLength()-20);
                    assertArrayEquals(NoiseTransport.PING, Arrays.copyOf(plain, n));
                    byte[] reply = new byte[20 + NoiseTransport.PONG.length + 16];
                    byte[] replyHeader = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                            .put((byte) 1).put((byte) 3).putLong(12345678L)
                            .putShort((short) 1).putLong(0).array();
                    System.arraycopy(replyHeader, 0, reply, 0, 20);
                    pair.getSender().setNonce(0);
                    pair.getSender().encryptWithAd(replyHeader, NoiseTransport.PONG, 0,
                            reply, 20, NoiseTransport.PONG.length);
                    server.send(new DatagramPacket(reply, reply.length, init.getSocketAddress()));
                    pair.destroy();
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "loopback-noise-test");
            worker.start();
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    server.getLocalPort()));
            try (NoiseTransport tunnel = NoiseTransport.handshake(client,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()),
                    profile)) {
                byte[] ping = tunnel.seal(NoiseTransport.PING, NoiseTransport.PING.length);
                client.send(new DatagramPacket(ping, ping.length));
                byte[] reply = new byte[4096];
                client.setSoTimeout(5000);
                DatagramPacket packet = new DatagramPacket(reply, reply.length);
                client.receive(packet);
                assertArrayEquals(NoiseTransport.PONG, tunnel.open(reply, packet.getLength()));
            } finally {
                worker.join(5000);
            }
            if (failure.get() != null) throw new AssertionError("Fake Noise server failed", failure.get());
            assertFalse(worker.isAlive());
        } finally {
            responder.destroy();
            clientIdentity.destroy();
        }
    }
}
