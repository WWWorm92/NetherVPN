package net.nether.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NetherVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "net.nether.vpn.CONNECT"
        const val ACTION_STOP = "net.nether.vpn.STOP"
        private const val CHANNEL = "nethervpn"
        private val PING = byteArrayOf(0) + "NVPN-PING-v1".toByteArray()
        private val PONG = byteArrayOf(0) + "NVPN-PONG-v1".toByteArray()
    }
    @Volatile private var stopping = false
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    private val up = AtomicLong()
    private val down = AtomicLong()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "NetherVPN", NotificationManager.IMPORTANCE_LOW))
    }
    private fun status(value: String) = ProfileStore.state(this, value, down.get(), up.get())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CONNECT || worker?.isAlive == true) return START_NOT_STICKY
        stopping = false
        up.set(0); down.set(0)
        val view = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, NetherVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(10, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("NetherVPN").setContentText("Защищённый тестовый туннель")
            .setContentIntent(view).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop).build())
        worker = Thread({ manage() }, "NetherVPN-Worker").apply { start() }
        return START_NOT_STICKY
    }

    private fun manage() {
        val profile = try {
            VpnProfile.parse(ProfileStore.load(this) ?: error("Нет профиля"))
        } catch (e: Exception) {
            status("Ошибка профиля: " + (e.message ?: "некорректный файл"))
            stopSelf(); return
        }
        var attempts = 0
        while (!stopping) {
            try {
                status("Подключение, попытка " + ++attempts)
                val remote = InetSocketAddress(InetAddress.getByName(profile.serverHost),
                    profile.serverPort)
                DatagramSocket().use { udp ->
                    sock = udp
                    check(protect(udp)) { "Не удалось защитить UDP-сокет от VPN-маршрута" }
                    udp.connect(remote)
                    udp.soTimeout = 1000
                    val session = handshake(udp, profile)
                    if (stopping) return
                    status("Сервер аутентифицирован, запускается TUN")
                    val device = Builder().setSession("NetherVPN").setMtu(profile.mtu)
                        .addAddress(profile.address, profile.prefix)
                        .addRoute(InetAddress.getByAddress(profile.serverTunnelAddress).hostAddress!!, 32)
                        .setBlocking(true).establish() ?: error("Android отказал в создании VPN")
                    tun = device
                    try { pumps(device, udp, session, profile) }
                    finally { device.close(); tun = null }
                }
            } catch (e: Exception) {
                if (!stopping) status("Сбой связи: " + (e.message?.take(100) ?: e.javaClass.simpleName))
            } finally {
                sock?.close(); sock = null
                tun?.close(); tun = null
            }
            if (!stopping) {
                val until = SystemClock.elapsedRealtime() + (attempts.coerceAtMost(5) * 1500L)
                while (!stopping && SystemClock.elapsedRealtime() < until)
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }
        status("Отключено")
    }

    private fun handshake(udp: DatagramSocket, p: VpnProfile): DatagramSession {
        val initiator = NoiseInitiator(p.privateKey, p.serverKey)
        val frame = initiator.start()
        repeat(12) {
            if (stopping) error("Подключение отменено")
            udp.send(DatagramPacket(frame, frame.size))
            try {
                val data = ByteArray(4096)
                val reply = DatagramPacket(data, data.size)
                udp.receive(reply)
                if (reply.length > 1 && data[0] == 0xA2.toByte())
                    return initiator.finish(data.copyOf(reply.length))
            } catch (_: SocketTimeoutException) { }
        }
        error("Нет ответа на Noise IK handshake")
    }

    private fun pumps(device: ParcelFileDescriptor, udp: DatagramSocket,
                      session: DatagramSession, p: VpnProfile) {
        val input = FileInputStream(device.fileDescriptor)
        val output = FileOutputStream(device.fileDescriptor)
        val readerError = AtomicReference<Throwable?>(null)
        val reader = Thread({
            try {
                val buf = ByteArray(p.mtu + 1)
                while (!stopping && !Thread.currentThread().isInterrupted) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > p.mtu || !WireCrypto.isIpPacket(buf, n)) continue
                    val frame = session.seal(buf.copyOf(n))
                    udp.send(DatagramPacket(frame, frame.size))
                    up.addAndGet(n.toLong())
                }
            } catch (e: Exception) {
                if (!stopping) readerError.set(e)
            } finally { udp.close() }
        }, "NetherVPN-TUN")
        reader.isDaemon = true
        reader.start()
        var pingAt = 0L
        var pongAt = SystemClock.elapsedRealtime()
        val buf = ByteArray(p.mtu + 512)
        try {
            while (!stopping && reader.isAlive) {
                val now = SystemClock.elapsedRealtime()
                if (now - pingAt >= 5000L) {
                    val frame = session.seal(PING)
                    udp.send(DatagramPacket(frame, frame.size))
                    pingAt = now
                }
                if (now - pongAt > 22000L) error("Keepalive timeout")
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udp.receive(packet)
                    val plain = try { session.open(packet.data.copyOf(packet.length)) }
                                catch (_: Exception) { continue }
                    if (Arrays.equals(plain, PONG)) {
                        pongAt = SystemClock.elapsedRealtime()
                        status("Сервер отвечает · split tunnel")
                        continue
                    }
                    if (plain.size > p.mtu || !WireCrypto.isIpPacket(plain)) continue
                    if (!Arrays.equals(WireCrypto.sourceAddress(plain), p.serverTunnelAddress)) continue
                    output.write(plain)
                    down.addAndGet(plain.size.toLong())
                    status("Сервер отвечает · split tunnel")
                } catch (_: SocketTimeoutException) {
                    readerError.get()?.let { throw IllegalStateException("TUN", it) }
                }
            }
            readerError.get()?.let { throw IllegalStateException("TUN", it) }
        } finally {
            device.close(); udp.close(); reader.interrupt()
            try { reader.join(1500) } catch (_: InterruptedException) { }
        }
    }

    private fun shutdown() {
        stopping = true
        sock?.close(); tun?.close(); worker?.interrupt()
        status("Отключено")
    }
    override fun onRevoke() { shutdown(); stopSelf(); super.onRevoke() }
    override fun onDestroy() { shutdown(); super.onDestroy() }
}
