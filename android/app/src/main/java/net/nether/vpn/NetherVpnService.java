package net.nether.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground, single-peer NetherVPN alpha. Routing is deliberately limited to
 * Peer.AllowedIPs (one host). Server does not implement general Internet NAT.
 */
public final class NetherVpnService extends VpnService {
    public static final String CONNECT = "net.nether.vpn.CONNECT";
    public static final String DISCONNECT = "net.nether.vpn.DISCONNECT";
    public static final String PREFS = "nether_status";
    private static final String CHANNEL = "nether_vpn_connection";
    private static final int NOTIFICATION_ID = 7;
    private final Object lifecycleLock = new Object();
    private final AtomicLong bytesUp = new AtomicLong();
    private final AtomicLong bytesDown = new AtomicLong();
    private volatile boolean running;
    private Thread worker;
    private DatagramSocket socket;
    private ParcelFileDescriptor tun;
    private long lastUiUpdate;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "NetherVPN connection", NotificationManager.IMPORTANCE_LOW));
        publish("Не подключено");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) {
            shutdown();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || !CONNECT.equals(intent.getAction())) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Подключение…"));
        synchronized (lifecycleLock) {
            if (running) return START_NOT_STICKY;
            running = true;
            bytesUp.set(0);
            bytesDown.set(0);
            worker = new Thread(this::connectionLoop, "NetherVPN-worker");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String state) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, NetherVpnService.class).setAction(DISCONNECT);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetherVPN · тестовая версия")
                .setContentText(state).setContentIntent(tap)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Отключить", stopAction).build())
                .build();
    }

    private void publish(String state) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("state", state)
                .putLong("tx", bytesUp.get())
                .putLong("rx", bytesDown.get())
                .apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (running) manager.notify(NOTIFICATION_ID, notification(state));
    }

    private void updateStats() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate > 1000) {
            lastUiUpdate = now;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("tx", bytesUp.get()).putLong("rx", bytesDown.get()).apply();
        }
    }

    private void connectionLoop() {
        int delay = 1;
        while (running) {
            NoiseTransport secure = null;
            try {
                VpnProfile profile = VpnProfile.parse(SecretStore.load(this));
                if (VpnService.prepare(this) != null) throw new IOException("Разрешение VPN отозвано");
                InetSocketAddress server = profile.endpoint();
                if (server.isUnresolved()) throw new IOException("DNS: сервер не найден");
                DatagramSocket udp = new DatagramSocket();
                synchronized (lifecycleLock) { socket = udp; }
                if (!protect(udp)) throw new IOException("Не удалось исключить UDP-сокет из VPN");
                udp.connect(server);
                publish("Noise IK: аутентификация…");
                secure = NoiseTransport.handshake(udp, server, profile);
                publish("Проверка зашифрованного обмена…");
                awaitPong(udp, secure);
                if (!running) break;

                Builder builder = new Builder()
                        .setSession("NetherVPN")
                        .setMtu(profile.mtu)
                        .addAddress(profile.localIp, profile.localPrefix)
                        .setBlocking(true);
                if (profile.fullTunnel) {
                    // All IPv4/DNS goes to the VPN, including public DNS resolvers.
                    builder.addRoute("0.0.0.0", 0).addDnsServer(profile.dns);
                    // Fail closed for IPv6 until server IPv6 forwarding exists:
                    // catch the default IPv6 route, then discard IPv6 from TUN.
                    builder.addAddress("fd77:77::2", 128).addRoute("::", 0);
                } else {
                    builder.addRoute(profile.peerIp, profile.peerPrefix);
                }
                ParcelFileDescriptor device = builder.establish();
                if (device == null) throw new IOException("Android отказал в создании VPN TUN");
                synchronized (lifecycleLock) { tun = device; }
                publish(profile.fullTunnel ? "Подключено · весь IPv4 через VPN" : "Подключено · только " + profile.peerIp);
                delay = 1;
                transfer(udp, secure, device, profile);
            } catch (Exception error) {
                if (running) publish("Ошибка: " + concise(error));
            } finally {
                closeCurrent();
                if (secure != null) secure.close();
            }
            if (running) {
                publish("Переподключение через " + delay + " с");
                try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                delay = Math.min(delay * 2, 8);
            }
        }
        publish("Не подключено");
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 110 ? message.substring(0, 110) : message;
    }

    private static void send(DatagramSocket socket, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length));
    }

    private void awaitPong(DatagramSocket udp, NoiseTransport secure) throws Exception {
        send(udp, secure.seal(NoiseTransport.PING, NoiseTransport.PING.length));
        long end = SystemClock.elapsedRealtime() + 5000;
        byte[] buffer = new byte[4096];
        while (running && SystemClock.elapsedRealtime() < end) {
            try {
                udp.setSoTimeout(500);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                byte[] plain = secure.open(buffer, packet.getLength());
                if (Arrays.equals(plain, NoiseTransport.PONG)) return;
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        throw new IOException("Нет зашифрованного PONG от сервера");
    }

    private void transfer(DatagramSocket udp, NoiseTransport secure, ParcelFileDescriptor device,
                          VpnProfile profile) throws Exception {
        FileInputStream input = new FileInputStream(device.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(device.getFileDescriptor());
        final java.util.concurrent.atomic.AtomicReference<Throwable> outgoingError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread outbound = new Thread(() -> {
            byte[] data = new byte[profile.mtu + 1];
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    int count = input.read(data);
                    if (count < 0) return;
                    if (count == 0 || count > profile.mtu) continue;
                    // IPv6 traffic is captured but deliberately not transmitted:
                    // gateway currently supports only routed/NATed IPv4.
                    if (!validOutbound(data, count, profile)) continue;
                    send(udp, secure.seal(data, count));
                    bytesUp.addAndGet(count);
                    updateStats();
                }
            } catch (Exception e) {
                if (running) outgoingError.set(e);
            }
        }, "NetherVPN-tun-to-udp");
        outbound.start();

        long lastPong = SystemClock.elapsedRealtime();
        long lastPing = lastPong;
        byte[] buffer = new byte[Math.max(4096, profile.mtu + 512)];
        try {
            while (running) {
                Throwable failure = outgoingError.get();
                if (failure != null) throw new IOException("Ошибка отправки: " + concise(failure), failure);
                long now = SystemClock.elapsedRealtime();
                if (now - lastPong > 20000) throw new IOException("Сервер не отвечает на keepalive");
                if (now - lastPing >= 5000) {
                    send(udp, secure.seal(NoiseTransport.PING, NoiseTransport.PING.length));
                    lastPing = now;
                }
                try {
                    udp.setSoTimeout(500);
                    DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                    udp.receive(incoming);
                    byte[] plain = secure.open(buffer, incoming.getLength());
                    if (plain == null) continue;
                    if (Arrays.equals(plain, NoiseTransport.PONG)) {
                        lastPong = SystemClock.elapsedRealtime();
                        continue;
                    }
                    if (!validFromPeer(plain, profile)) continue;
                    output.write(plain);
                    bytesDown.addAndGet(plain.length);
                    updateStats();
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } finally {
            try { device.close(); } catch (IOException ignored) { }
            outbound.interrupt();
            try { outbound.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Authenticated encrypted return packets must be IPv4 destined to this client. */
    private static boolean validFromPeer(byte[] packet, VpnProfile profile) {
        if (!validIPv4(packet, packet.length)) return false;
        if (!addressEquals(packet, 16, profile.localBytes)) return false;
        return profile.fullTunnel || addressEquals(packet, 12, profile.peerBytes);
    }

    /** Drop IPv6 and reject any source address not assigned by the server. */
    private static boolean validOutbound(byte[] packet, int length, VpnProfile profile) {
        return validIPv4(packet, length) && addressEquals(packet, 12, profile.localBytes);
    }

    private static boolean validIPv4(byte[] packet, int length) {
        if (length < 20 || (packet[0] >>> 4 & 15) != 4) return false;
        int ihl = (packet[0] & 15) * 4;
        int total = (packet[2] & 255) * 256 + (packet[3] & 255);
        return ihl >= 20 && ihl <= length && total == length;
    }

    private static boolean addressEquals(byte[] packet, int offset, byte[] ip) {
        if (ip.length != 4 || packet.length < offset + 4) return false;
        for (int i = 0; i < 4; i++) {
            if (packet[offset + i] != ip[i]) return false;
        }
        return true;
    }

    private void closeCurrent() {
        synchronized (lifecycleLock) {
            if (tun != null) { try { tun.close(); } catch (IOException ignored) { } tun = null; }
            if (socket != null) { socket.close(); socket = null; }
        }
    }

    private void shutdown() {
        running = false;
        closeCurrent();
        if (worker != null) worker.interrupt();
        publish("Не подключено");
    }

    @Override public void onRevoke() { shutdown(); stopSelf(); }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
