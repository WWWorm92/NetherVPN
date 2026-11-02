package net.nether.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** A user-consented Android VPN client, not a WireGuard client. */
class MainActivity : Activity() {
    companion object {
        private const val PERMISSION_REQUEST = 52
        private const val IMPORT_REQUEST = 53
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var editor: EditText
    private lateinit var panel: LinearLayout
    private val refresh = object : Runnable {
        override fun run() {
            val state = ProfileStore.status(this@MainActivity)
            status.text = state.first
            stats.text = "↑ " + (state.third / 1024) + " KiB    ↓ " + (state.second / 1024) + " KiB"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 20, 32)
        window.navigationBarColor = Color.rgb(13, 20, 32)
        val scroll = ScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 42, 32, 32)
            setBackgroundColor(Color.rgb(15, 23, 38))
        }
        scroll.addView(panel)
        setContentView(scroll)

        addText("◈ NetherVPN", 27f, Color.rgb(66, 218, 189))
        addText("Android · 0.2.0 alpha · лабораторная версия", 13f, Color.LTGRAY)
        addText("Состояние подключения", 15f, Color.LTGRAY)
        status = addText("Отключено", 21f, Color.WHITE)
        stats = addText("↑ 0 KiB    ↓ 0 KiB", 15f, Color.rgb(66, 218, 189))

        addButton("Подключить", true) {
            if (!ProfileStore.hasProfile(this)) {
                toast("Импортируйте и сохраните VPN-профиль")
                return@addButton
            }
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, PERMISSION_REQUEST)
            else connect()
        }
        addButton("Отключить", false) {
            startService(Intent(this, NetherVpnService::class.java).setAction(NetherVpnService.ACTION_STOP))
        }

        addText("Профиль подключения", 18f, Color.WHITE)
        addText(
            "Формат NetherVPN .conf. Ключи шифруются Android Keystore. " +
                "Не используйте конфигурацию WireGuard: протоколы несовместимы.",
            13f, Color.LTGRAY
        )
        editor = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            minLines = 7
            maxLines = 12
            gravity = Gravity.TOP
            hint = "[Interface]\nPrivateKey = (64 hex)\nAddress = 10.77.0.2/24\n" +
                "MTU = 1280\n\n[Peer]\nPublicKey = (64 hex)\n" +
                "Endpoint = vpn.example.com:51830\nAllowedIPs = 10.77.0.1"
            setBackgroundColor(Color.rgb(30, 44, 63))
            setPadding(16, 16, 16, 16)
        }
        panel.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addButton("Импортировать .conf", false) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, IMPORT_REQUEST)
        }
        addButton("Сохранить профиль (зашифрованно)", true) {
            try {
                ProfileStore.save(this, editor.text.toString())
                editor.text.clear()
                toast("Профиль сохранён")
            } catch (e: Exception) {
                toast("Ошибка профиля: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        addButton("Редактировать сохранённый профиль", false) {
            try {
                val value = ProfileStore.load(this)
                if (value == null) toast("Профиль не сохранён")
                else editor.setText(value)
            } catch (e: Exception) { toast("Не удалось прочитать профиль") }
        }
        addText(
            "Важно: это экспериментальный VPN. В текущем режиме направляется " +
                "только трафик на туннельный адрес сервера (split tunnel). " +
                "Общий интернет не защищается; для полноценного VPN нужны " +
                "серверные NAT, маршруты, DNS/IPv6-политика и аудит протокола.",
            13f, Color.rgb(239, 189, 120)
        )
    }

    private fun addText(value: String, size: Float, color: Int): TextView {
        val label = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 10, 0, 12)
        }
        panel.addView(label)
        return label
    }

    private fun addButton(value: String, primary: Boolean, action: () -> Unit) {
        val button = Button(this).apply {
            text = value
            isAllCaps = false
            setTextColor(if (primary) Color.rgb(5, 21, 31) else Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                if (primary) Color.rgb(66, 218, 189) else Color.rgb(52, 72, 96)))
            setOnClickListener { action() }
        }
        panel.addView(button)
    }

    private fun connect() {
        try {
            startForegroundService(Intent(this, NetherVpnService::class.java)
                .setAction(NetherVpnService.ACTION_CONNECT))
        } catch (e: Exception) {
            toast("Не удалось запустить VPN: " + e.javaClass.simpleName)
        }
    }

    @Deprecated("Android VPN consent uses legacy activity result for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) connect() else toast("Доступ к VPN не предоставлен")
        } else if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK) {
            try {
                val stream = contentResolver.openInputStream(data?.data
                    ?: throw IllegalArgumentException("Не выбран файл"))
                val bytes = stream?.use { it.readBytes() } ?: throw IllegalArgumentException("Пустой файл")
                require(bytes.size <= 64 * 1024) { "Слишком большой файл" }
                editor.setText(bytes.toString(Charsets.UTF_8))
                toast("Профиль загружен. Нажмите «Сохранить»")
            } catch (e: Exception) { toast("Не удалось импортировать: " + e.message) }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
