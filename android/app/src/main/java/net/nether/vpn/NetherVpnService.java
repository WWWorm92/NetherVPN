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
import android.util.Log;
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
 * Foreground NetherVPN alpha. Traffic policy is selected locally, independently
 * of the server-issued identity profile. Internet full tunnel requires gateway NAT.
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
    private final AtomicLong droppedPackets = new AtomicLong();
    private final AtomicLong inboundWire = new AtomicLong();
    private final AtomicLong inboundDecrypted = new AtomicLong();
    private final AtomicLong inboundWritten = new AtomicLong();
    private final AtomicLong invalidInbound = new AtomicLong();
    private final AtomicLong outboundDropped = new AtomicLong();
    private final AtomicLong inboundDropped = new AtomicLong();
    private final AtomicLong ipv6Dropped = new AtomicLong();
    private volatile long connectedSinceElapsed = 0;
    private volatile String phase = "disconnected";
    private volatile String lastError = "";
    private volatile String stage = "idle";
    private volatile String failedStage = "";
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
        stage = "idle";
        publish("Не подключено", "disconnected");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) {
            shutdown();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android can start an Always-on VpnService without our CONNECT action.
        // Only start after VPN consent and a previously imported identity.
        if (intent != null && intent.getAction() != null &&
                !CONNECT.equals(intent.getAction()) &&
                !VpnService.SERVICE_INTERFACE.equals(intent.getAction()))
            return START_NOT_STICKY;
        if (!SecretStore.exists(this) || VpnService.prepare(this) != null) {
            lastError = !SecretStore.exists(this) ? "Сначала импортируйте профиль"
                    : "Android не предоставил разрешение VPN";
            publish(lastError, "error");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Подключение…"));
        synchronized (lifecycleLock) {
            if (running) return START_NOT_STICKY;
            running = true;
            bytesUp.set(0);
            bytesDown.set(0);
            droppedPackets.set(0);
            inboundWire.set(0);
            inboundDecrypted.set(0);
            inboundWritten.set(0);
            invalidInbound.set(0);
            outboundDropped.set(0);
            inboundDropped.set(0);
            ipv6Dropped.set(0);
            connectedSinceElapsed = 0;
            lastError = "";
            failedStage = "";
            stage = "start";
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
                .setSmallIcon(R.drawable.ic_nethervpn)
                .setContentTitle("NetherVPN · тестовая версия")
                .setContentText(state).setContentIntent(tap)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Отключить", stopAction).build())
                .build();
    }

    private void publish(String state, String newPhase) {
        phase = newPhase;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("state", state)
                .putString("phase", newPhase)
                .putString("last_error", lastError)
                .putString("stage", stage)
                .putString("failed_stage", failedStage)
                .putLong("since_elapsed", connectedSinceElapsed)
                .putLong("updated_elapsed", SystemClock.elapsedRealtime())
                .putLong("tx", bytesUp.get())
                .putLong("rx", bytesDown.get())
                .putLong("dropped", droppedPackets.get())
                .putLong("wire_rx", inboundWire.get())
                .putLong("decrypted_rx", inboundDecrypted.get())
                .putLong("written_rx", inboundWritten.get())
                .putLong("invalid_rx", invalidInbound.get())
                .putLong("outbound_drops", outboundDropped.get())
                .putLong("inbound_drops", inboundDropped.get())
                .putLong("ipv6_drops", ipv6Dropped.get())
                .apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (running) manager.notify(NOTIFICATION_ID, notification(state));
    }

    /** Keep diagnostic stages in SharedPreferences without persisting keys. */
    private void step(String code, String description) {
        stage = code;
        Log.i("NetherVPN", "VPN stage: " + code);
        publish(description, "connecting");
    }

    /** Rejected TUN packets are normal on dual-stack Android networks.
     * Never log their contents and never destroy an authenticated session. */
    private void noteDrop(int reason, int bytes, boolean inbound) {
        if (inbound) inboundDropped.incrementAndGet();
        else outboundDropped.incrementAndGet();
        if (reason == PacketPolicy.IPV6_UNSUPPORTED) ipv6Dropped.incrementAndGet();
        long count = droppedPackets.incrementAndGet();
        if (count <= 3 || (count & (count - 1)) == 0) {
            Log.i("NetherVPN", "Dropped IP packet: " + PacketPolicy.reason(reason)
                    + " (" + bytes + " B, total " + count + "); session remains active");
        }
        updateStats();
    }

    private void updateStats() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate > 1000) {
            lastUiUpdate = now;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("tx", bytesUp.get()).putLong("rx", bytesDown.get())
                    .putLong("dropped", droppedPackets.get())
                    .putLong("wire_rx", inboundWire.get())
                    .putLong("decrypted_rx", inboundDecrypted.get())
                    .putLong("written_rx", inboundWritten.get())
                    .putLong("invalid_rx", invalidInbound.get())
                    .putLong("outbound_drops", outboundDropped.get())
                    .putLong("inbound_drops", inboundDropped.get())
                    .putLong("ipv6_drops", ipv6Dropped.get())
                    .putLong("updated_elapsed", now).apply();
        }
    }

    private void connectionLoop() {
        int delay = 1;
        while (running) {
            NoiseTransport secure = null;
            try {
                step("profile", "Проверка конфигурации…");
                VpnProfile profile = VpnProfile.parse(SecretStore.load(this));
                ClientSettings settings = ClientSettings.load(this, profile);
                if (settings.fullTunnel && (!profile.endpointHost.matches("[0-9.]+")
                    || profile.localBytes.length != 4))
                    throw new IOException("Полный туннель требует числовой IPv4-адрес сервера и клиента");
                if (VpnService.prepare(this) != null) throw new IOException("Разрешение VPN отозвано");
                step("endpoint", "Поиск адреса сервера…");
                InetSocketAddress server = profile.endpoint();
                if (server.isUnresolved()) throw new IOException("DNS: сервер не найден");
                step("udp", "Открытие UDP-сокета…");
                DatagramSocket udp = new DatagramSocket();
                synchronized (lifecycleLock) { socket = udp; }
                if (!protect(udp)) throw new IOException("Не удалось исключить UDP-сокет из VPN");
                udp.connect(server);
                step("noise_ik", "Noise IK: аутентификация…");
                secure = NoiseTransport.handshake(udp, server, profile);
                step("encrypted_ping", "Проверка зашифрованного обмена…");
                awaitPong(udp, secure);
                if (!running) break;

                step("tun", "Создание интерфейса Android VPN…");
                Builder builder = new Builder()
                        .setSession("NetherVPN")
                        .setMtu(profile.mtu)
                        .addAddress(profile.localIp, profile.localPrefix)
                        .setBlocking(true);
                if (settings.fullTunnel) {
                    // All IPv4/DNS goes to the VPN, including public DNS resolvers.
                    builder.addRoute("0.0.0.0", 0).addDnsServer(settings.dns);
                    // Fail closed for IPv6 until server IPv6 forwarding exists:
                    // catch the default IPv6 route, then discard IPv6 from TUN.
                    builder.addAddress("fd77:77::2", 128).addRoute("::", 0);
                } else {
                    builder.addRoute(profile.peerIp, profile.peerPrefix);
                }
                ParcelFileDescriptor device = builder.establish();
                if (device == null) throw new IOException("Android отказал в создании VPN TUN");
                synchronized (lifecycleLock) { tun = device; }
                connectedSinceElapsed = SystemClock.elapsedRealtime();
                lastError = "";
                stage = "connected";
                failedStage = "";
                publish(settings.fullTunnel ? "Подключено · весь IPv4 через VPN" : "Подключено · только " + profile.peerIp, "connected");
                delay = 1;
                transfer(udp, secure, device, profile, settings.fullTunnel);
            } catch (Exception error) {
                if (running) {
                    failedStage = stage;
                    lastError = "Этап " + failedStage + ": " + concise(error);
                    Log.e("NetherVPN", "VPN connection failed: " + concise(error));
                    publish(lastError, "error");
                }
            } finally {
                closeCurrent();
                if (secure != null) secure.close();
            }
            if (running) {
                connectedSinceElapsed = 0;
                stage = "retry";
                publish(lastError.isEmpty() ? "Переподключение через " + delay + " с"
                        : lastError + " · повтор через " + delay + " с", "reconnecting");
                try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                delay = Math.min(delay * 2, 8);
            }
        }
        publish("Не подключено", "disconnected");
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
        // UDP can lose the first encrypted ping, particularly on mobile data.
        // Every retry is encrypted with a new nonce, never reuses a ciphertext.
        final long end = SystemClock.elapsedRealtime() + 6500;
        long nextPing = 0;
        byte[] buffer = new byte[4096];
        int received = 0;
        while (running && SystemClock.elapsedRealtime() < end) {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextPing) {
                send(udp, secure.seal(NoiseTransport.PING, NoiseTransport.PING.length));
                nextPing = now + 1200;
            }
            try {
                udp.setSoTimeout(350);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                received++;
                byte[] plain = secure.open(buffer, packet.getLength());
                if (Arrays.equals(plain, NoiseTransport.PONG)) {
                    Log.i("NetherVPN", "Authenticated encrypted ping/pong confirmed");
                    return;
                }
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        throw new IOException(received == 0
                ? "UDP: нет ответа на зашифрованный PING"
                : "Ответ UDP получен (" + received + "), но PONG отвергнут: "
                    + secure.lastRejectReason());
    }

    private void transfer(DatagramSocket udp, NoiseTransport secure, ParcelFileDescriptor device,
                          VpnProfile profile, boolean fullTunnel) throws Exception {
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
                    if (count == 0) continue;
                    if (count > profile.mtu) {
                        noteDrop(PacketPolicy.MALFORMED, count, false);
                        continue;
                    }
                    // IPv6 traffic is captured but deliberately not transmitted:
                    // gateway currently supports only routed/NATed IPv4.
                    int verdict = PacketPolicy.outbound(data, count, profile.localBytes);
                    if (verdict != PacketPolicy.OK) {
                        noteDrop(verdict, count, false);
                        continue;
                    }
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
                    inboundWire.incrementAndGet();
                    byte[] plain = secure.open(buffer, incoming.getLength());
                    if (plain == null) {
                        invalidInbound.incrementAndGet();
                        updateStats();
                        continue;
                    }
                    inboundDecrypted.incrementAndGet();
                    if (Arrays.equals(plain, NoiseTransport.PONG)) {
                        lastPong = SystemClock.elapsedRealtime();
                        // Refresh the UI heartbeat on authenticated keepalive,
                        // even when no application data is flowing.
                        updateStats();
                        continue;
                    }
                    int verdict = PacketPolicy.inbound(plain, plain.length, profile.localBytes,
                            profile.peerBytes, fullTunnel);
                    if (verdict != PacketPolicy.OK) {
                        noteDrop(verdict, plain.length, true);
                        continue;
                    }
                    output.write(plain);
                    inboundWritten.incrementAndGet();
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

    private void closeCurrent() {
        synchronized (lifecycleLock) {
            if (tun != null) { try { tun.close(); } catch (IOException ignored) { } tun = null; }
            if (socket != null) { socket.close(); socket = null; }
        }
    }

    private void shutdown() {
        running = false;
        connectedSinceElapsed = 0;
        closeCurrent();
        if (worker != null) worker.interrupt();
        publish("Не подключено", "disconnected");
    }

    @Override public void onRevoke() { shutdown(); stopSelf(); }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
