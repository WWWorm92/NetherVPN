package net.nether.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Minimal Android 8+ configuration and status screen. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var traffic: TextView
    private lateinit var profileStatus: TextView
    private val requestVpn = 21
    private val requestFile = 22
    private val colors = intArrayOf(Color.rgb(14, 22, 38), Color.rgb(25, 38, 59),
        Color.rgb(72, 206, 178), Color.WHITE, Color.rgb(164, 184, 207))

    private val poll = object : Runnable {
        override fun run() {
            val preferences = getSharedPreferences(NetherVpnService.PREFS, MODE_PRIVATE)
            status.text = preferences.getString("state", "Не подключено")
            val tx = preferences.getLong("tx", 0)
            val rx = preferences.getLong("rx", 0)
            traffic.text = String.format(Locale.US, "↑ %.1f КиБ     ↓ %.1f КиБ",
                tx / 1024.0, rx / 1024.0)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = ScrollView(this).apply { setBackgroundColor(colors[0]) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 55, 32, 40)
        }
        outer.addView(panel)
        fun label(value: String, size: Float, color: Int = colors[3]): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, 12)
            }
        fun action(value: String, click: () -> Unit) {
            val button = Button(this).apply {
                text = value
                isAllCaps = false
                setOnClickListener { click() }
            }
            panel.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(label("◈  NetherVPN", 29f))
        panel.addView(label("Android · экспериментальный VPN-клиент", 14f, colors[4]))
        panel.addView(label("СОСТОЯНИЕ СОЕДИНЕНИЯ", 12f, colors[2]))
        status = label("Не подключено", 21f)
        traffic = label("↑ 0 КиБ     ↓ 0 КиБ", 15f, colors[2])
        panel.addView(status)
        panel.addView(traffic)
        action("Подключить") { connect() }
        action("Отключить") {
            startService(Intent(this, NetherVpnService::class.java)
                .setAction(NetherVpnService.DISCONNECT))
        }
        action("Настройки Always-on VPN и блокировки утечек") {
            startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
        }
        panel.addView(label("ПРОФИЛЬ (.conf)", 15f, colors[2]))
        profileStatus = label("Профиль не загружен", 14f, colors[4])
        panel.addView(profileStatus)
        action("Импортировать файл .conf") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, requestFile)
        }
        panel.addView(label("Или вставьте конфигурацию ниже и сохраните", 13f, colors[4]))
        editor = EditText(this).apply {
            minLines = 6
            maxLines = 12
            setTextColor(colors[3])
            setHintTextColor(colors[4])
            hint = "[Interface]\nPrivateKey = ...\nAddress = 10.77.0.3/32\nFullTunnel = true\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = ...\nEndpoint = 45.151.69.184:51830\nAllowedIPs = 10.77.0.1"
            setBackgroundColor(colors[1])
            setPadding(16, 16, 16, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        panel.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        action("Сохранить профиль") {
            try {
                SecretStore.save(this, editor.text.toString())
                editor.text.clear() // Avoid lingering private keys in the editor.
                updateProfile()
                toast("Профиль сохранён с шифрованием Android Keystore")
            } catch (e: Exception) {
                toast("Ошибка: ${e.message}")
            }
        }
        action("Удалить профиль") {
            SecretStore.clear(this)
            editor.text.clear()
            updateProfile()
            toast("Сохранённый профиль удалён")
        }
        panel.addView(label(
            "FullTunnel = true: весь IPv4 и DNS направляется через VPN; IPv6 блокируется. " +
            "Для выхода в интернет на сервере требуется NAT. " +
            "На Android включите «Постоянная VPN» и «Блокировать подключения без VPN», " +
            "чтобы не допустить прямого трафика во время переподключения. " +
            "Экспериментальная версия, не прошедшая аудит безопасности.", 13f, colors[4]))
        setContentView(outer)
        updateProfile()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 55)
        }
    }

    private fun updateProfile() {
        profileStatus.text = try {
            if (!SecretStore.exists(this)) "Профиль не загружен"
            else {
                val profile = VpnProfile.parse(SecretStore.load(this))
                if (profile.fullTunnel) "Профиль сохранён · весь IPv4 через VPN"
                else "Профиль сохранён · маршрут только к серверу"
            }
        } catch (_: Exception) { "Ошибка чтения сохранённого профиля" }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun connect() {
        try {
            if (editor.text.isNotBlank()) {
                SecretStore.save(this, editor.text.toString())
                editor.text.clear()
                updateProfile()
            }
            VpnProfile.parse(SecretStore.load(this))
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, requestVpn)
            else startTunnel()
        } catch (e: Exception) {
            toast("Ошибка конфигурации: ${e.message}")
        }
    }

    private fun startTunnel() {
        val intent = Intent(this, NetherVpnService::class.java).setAction(NetherVpnService.CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    @Deprecated("Platform VPN consent result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVpn) {
            if (resultCode == RESULT_OK) startTunnel() else toast("Разрешение VPN не предоставлено")
        }
        if (requestCode == requestFile && resultCode == RESULT_OK && data?.data != null) {
            try {
                val bytes = contentResolver.openInputStream(data.data!!)!!.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (out.size() + n > 65536) throw IllegalArgumentException("Файл слишком большой")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                SecretStore.save(this, String(bytes, StandardCharsets.UTF_8))
                editor.text.clear()
                updateProfile()
                toast("Профиль импортирован")
            } catch (e: Exception) {
                toast("Ошибка импорта: ${e.message}")
            }
        }
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
}
