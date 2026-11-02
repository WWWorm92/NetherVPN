package net.nether.vpn;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** NetherVPN v1 wire framing. Uses Noise-Java, not homemade crypto. */
public final class NoiseTransport implements AutoCloseable {
    public static final byte[] PING = "\u0000NVPN-PING-v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] PONG = "\u0000NVPN-PONG-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = 20;
    private static final int TAG = 16;
    private final CipherStatePair pair;
    private final long connectionId;
    private final int epoch;
    private long sendNext;
    private boolean replayInitialized;
    private long replayHighest;
    private long replayBits;

    private NoiseTransport(CipherStatePair pair, long id, int epoch) {
        this.pair = pair; this.connectionId = id; this.epoch = epoch;
    }

    /** The server sends A2 containing a 10-byte authenticated session identifier. */
    public static NoiseTransport handshake(DatagramSocket socket, InetSocketAddress server,
                                            VpnProfile profile) throws Exception {
        HandshakeState state = new HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.INITIATOR);
        try {
            state.getLocalKeyPair().setPrivateKey(profile.privateKey, 0);
            state.getRemotePublicKey().setPublicKey(profile.serverPublicKey, 0);
            byte[] prologue = "NetherVPN/noise-ik/v1".getBytes(StandardCharsets.US_ASCII);
            state.setPrologue(prologue, 0, prologue.length);
            state.start();
            byte[] init = new byte[256];
            int initLen = state.writeMessage(init, 1, null, 0, 0);
            init[0] = (byte) 0xA1;

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            byte[] input = new byte[4096];
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                socket.send(new DatagramPacket(init, initLen + 1, server));
                long retry = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime() < retry && System.nanoTime() < deadline) {
                    try {
                        socket.setSoTimeout(250);
                        DatagramPacket incoming = new DatagramPacket(input, input.length);
                        socket.receive(incoming);
                        if (!incoming.getAddress().equals(server.getAddress()) || incoming.getPort() != server.getPort()
                                || incoming.getLength() < 2 || input[0] != (byte) 0xA2) continue;
                        byte[] payload = new byte[256];
                        int len = state.readMessage(input, 1, incoming.getLength() - 1, payload, 0);
                        if (len != 10) throw new IOException("Неверные параметры сессии");
                        ByteBuffer params = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                        long id = params.getLong();
                        int epoch = Short.toUnsignedInt(params.getShort());
                        if (id == 0 || epoch != 1) throw new IOException("Неверный идентификатор сессии");
                        return new NoiseTransport(state.split(), id, epoch);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // Resend the exact same A1 frame; server caches A2 for retransmission.
                    }
                }
            }
            throw new IOException("Нет ответа Noise IK от сервера");
        } finally {
            state.destroy();
        }
    }

    public synchronized byte[] seal(byte[] plain, int length) throws Exception {
        if (length < 0 || length > plain.length) throw new IllegalArgumentException("length");
        if (sendNext == Long.MAX_VALUE - 1) throw new IOException("Требуется новый Noise handshake");
        long counter = sendNext++;
        byte[] header = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 1).put((byte) 3).putLong(connectionId).putShort((short) epoch)
                .putLong(counter).array();
        byte[] out = new byte[HEADER + length + TAG];
        System.arraycopy(header, 0, out, 0, HEADER);
        CipherState cipher = pair.getSender();
        cipher.setNonce(counter);
        int encoded = cipher.encryptWithAd(header, plain, 0, out, HEADER, length);
        if (encoded != length + TAG) throw new IOException("Шифрование вернуло неверную длину");
        return out;
    }

    /** Authentication happens before replay-window changes. Returns null for invalid/duplicate packets. */
    public synchronized byte[] open(byte[] packet, int length) {
        if (length < HEADER + TAG || packet[0] != 1 || packet[1] != 3) return null;
        ByteBuffer h = ByteBuffer.wrap(packet, 0, HEADER).order(ByteOrder.BIG_ENDIAN);
        h.position(2);
        long id = h.getLong();
        int packetEpoch = Short.toUnsignedInt(h.getShort());
        long counter = h.getLong();
        if (id != connectionId || packetEpoch != epoch || counter < 0) return null;
        byte[] header = Arrays.copyOfRange(packet, 0, HEADER);
        byte[] plain = new byte[length - HEADER - TAG];
        try {
            CipherState cipher = pair.getReceiver();
            cipher.setNonce(counter);
            int decoded = cipher.decryptWithAd(header, packet, HEADER, plain, 0, length - HEADER);
            if (decoded != plain.length || !accept(counter)) return null;
            return plain;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean accept(long counter) {
        if (!replayInitialized) {
            replayInitialized = true; replayHighest = counter; replayBits = 1L; return true;
        }
        if (counter > replayHighest) {
            long shift = counter - replayHighest;
            replayBits = (shift >= 64 ? 0 : replayBits << (int) shift) | 1L;
            replayHighest = counter;
            return true;
        }
        long behind = replayHighest - counter;
        if (behind >= 64 || ((replayBits >>> (int) behind) & 1L) != 0) return false;
        replayBits |= 1L << (int) behind;
        return true;
    }

    @Override public void close() { pair.destroy(); }
}
