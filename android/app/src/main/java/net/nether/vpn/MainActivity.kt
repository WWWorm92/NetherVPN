package net.nether.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Native NetherVPN dashboard, matched to the Linux client's visual language. */
class MainActivity : Activity() {
    private val navy = Color.rgb(10, 18, 31)
    private val card = Color.rgb(20, 34, 51)
    private val raised = Color.rgb(29, 48, 69)
    private val mint = Color.rgb(58, 217, 184)
    private val white = Color.rgb(241, 247, 255)
    private val muted = Color.rgb(157, 179, 201)
    private val amber = Color.rgb(255, 192, 103)
    private val red = Color.rgb(255, 114, 130)
    private val handler = Handler(Looper.getMainLooper())
    private val vpnRequest = 21
    private val fileRequest = 22
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var badge: TextView
    private lateinit var connectButton: Button
    private lateinit var endpoint: TextView
    private lateinit var profile: TextView
    private lateinit var up: TextView
    private lateinit var down: TextView
    private lateinit var dropped: TextView
    private lateinit var uptime: TextView
    private lateinit var full: RadioButton
    private lateinit var split: RadioButton
    private lateinit var dns: EditText
    private var phase = "disconnected"

    private fun dp(x: Int) = (x * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
    private fun caption(value: String) = text(value, 11f, muted, true).apply {
        letterSpacing = .09f
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(card, 22)
        setPadding(dp(19), dp(18), dp(19), dp(19))
    }
    private fun space(parent: LinearLayout, size: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }
    private fun addCard(parent: LinearLayout, item: LinearLayout) {
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun button(label: String, primary: Boolean = false, click: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (primary) navy else white)
            background = shape(if (primary) mint else raised, 13)
            minHeight = dp(50)
            minimumHeight = dp(50)
            setOnClickListener { click() }
        }
    private fun stat(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(raised, 14)
        setPadding(dp(12), dp(10), dp(10), dp(13))
        addView(caption(label))
        space(this, 5)
        addView(value)
    }
    private fun statRow(left: LinearLayout, right: LinearLayout) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = dp(5)
            })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(5)
            })
        }

    private val updater = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        val scroll = ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(26))
        }
        scroll.addView(page)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(text("◈", 32f, mint, true).apply {
            gravity = Gravity.CENTER
            background = shape(raised, 15)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        brand.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
            addView(text("NetherVPN", 26f, white, true))
            addView(caption("ЗАЩИЩЁННАЯ СЕТЬ  ·  ANDROID"))
        })
        page.addView(brand)
        space(page, 21)

        val hero = panel()
        hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31, 65, 80), Color.rgb(17, 35, 55))).apply {
            cornerRadius = dp(24).toFloat()
        }
        hero.addView(caption("ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ"))
        space(hero, 14)
        badge = text("●  НЕ ПОДКЛЮЧЕНО", 11f, muted, true)
        hero.addView(badge)
        space(hero, 8)
        status = text("VPN выключен", 27f, white, true)
        hero.addView(status)
        space(hero, 5)
        detail = text("Импортируйте профиль и подключитесь", 13f, muted)
        hero.addView(detail)
        space(hero, 21)
        connectButton = button("◈  Подключить", true) {
            if (phase == "disconnected") connect() else disconnect()
        }
        hero.addView(connectButton, LinearLayout.LayoutParams(-1, dp(54)))
        space(hero, 12)
        endpoint = text("Сервер: —", 12f, muted)
        hero.addView(endpoint)
        addCard(page, hero)

        val metrics = panel()
        metrics.addView(caption("СТАТИСТИКА СЕССИИ"))
        space(metrics, 13)
        up = text("0 Б", 19f, white, true)
        down = text("0 Б", 19f, white, true)
        dropped = text("0", 19f, white, true)
        uptime = text("00:00:00", 19f, white, true)
        metrics.addView(statRow(stat("↑ ОТПРАВЛЕНО", up), stat("↓ ПОЛУЧЕНО", down)))
        space(metrics, 10)
        metrics.addView(statRow(stat("ОТБРОШЕНО", dropped), stat("ВРЕМЯ", uptime)))
        addCard(page, metrics)

        val routing = panel()
        routing.addView(caption("ПОДКЛЮЧЕНИЕ"))
        space(routing, 8)
        routing.addView(text("Маршрутизация", 19f, white, true))
        space(routing, 8)
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        full = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Весь IPv4 через VPN"
            textSize = 15f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(mint)
        }
        split = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Только VPN-сервер"
            textSize = 15f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(mint)
        }
        choices.addView(full)
        choices.addView(split)
        routing.addView(choices)
        space(routing, 8)
        routing.addView(text("DNS-сервер для полного туннеля", 12f, muted))
        space(routing, 7)
        dns = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "1.1.1.1"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            background = shape(raised, 11)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        routing.addView(dns, LinearLayout.LayoutParams(-1, dp(49)))
        space(routing, 12)
        routing.addView(button("Сохранить настройки") {
            try {
                ClientSettings.save(this, full.isChecked, dns.text.toString())
                updateProfile()
                Toast.makeText(this, "Сохранено. Переподключите VPN для применения.",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) { problem("Настройки", e) }
        })
        space(routing, 12)
        routing.addView(text("В Full Tunnel IPv6 перехватывается и блокируется. " +
            "На шлюзе необходим NAT для IPv4.", 12f, muted))
        addCard(page, routing)

        val credentials = panel()
        credentials.addView(caption("ПРОФИЛЬ"))
        space(credentials, 8)
        profile = text("Профиль не загружен", 17f, white, true)
        credentials.addView(profile)
        space(credentials, 14)
        credentials.addView(button("＋  Импортировать .conf", true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, fileRequest)
        })
        space(credentials, 9)
        credentials.addView(button("Вставить конфигурацию") { paste() })
        space(credentials, 9)
        credentials.addView(button("Удалить профиль") { deleteProfile() })
        space(credentials, 12)
        credentials.addView(text("Приватный ключ хранится в Android Keystore. " +
            "Не публикуйте и не пересылайте свой .conf посторонним.", 12f, muted))
        addCard(page, credentials)

        val privacy = panel()
        privacy.addView(caption("БЕЗОПАСНОСТЬ"))
        space(privacy, 8)
        privacy.addView(text("Постоянная VPN", 19f, white, true))
        space(privacy, 9)
        privacy.addView(text("В Android включите «Постоянная VPN» и «Блокировать " +
            "подключения без VPN». Это предотвращает прямой трафик при обрыве.", 12f, muted))
        space(privacy, 12)
        privacy.addView(button("Настройки VPN в Android  ↗") {
            startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
        })
        addCard(page, privacy)
        page.addView(text("NetherVPN  ·  0.5 Alpha  ·  Noise IK / IPv4\n" +
            "Экспериментальный VPN. Независимый аудит безопасности не проводился.",
            11f, muted))
        setContentView(scroll)
        updateProfile()
        updateStatus()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 55)
        }
    }

    private fun bytes(value: Long): String = when {
        value < 1024 -> value.toString() + " Б"
        value < 1024L * 1024 -> String.format(Locale.US, "%.1f КиБ", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.US,
            "%.1f МиБ", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f ГиБ",
            value / (1024.0 * 1024.0 * 1024.0))
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences(NetherVpnService.PREFS, MODE_PRIVATE)
        phase = prefs.getString("phase", "disconnected") ?: "disconnected"
        val connected = phase == "connected"
        val pending = phase == "connecting" || phase == "reconnecting"
        badge.setTextColor(if (connected) mint else if (pending) amber
                           else if (phase == "error") red else muted)
        badge.text = when (phase) {
            "connected" -> "●  СОЕДИНЕНИЕ ЗАЩИЩЕНО"
            "connecting" -> "●  ПОДКЛЮЧЕНИЕ"
            "reconnecting" -> "●  ПЕРЕПОДКЛЮЧЕНИЕ"
            "error" -> "●  ОШИБКА"
            else -> "●  НЕ ПОДКЛЮЧЕНО"
        }
        status.text = when (phase) {
            "connected" -> "Подключено"
            "connecting" -> "Подключаемся…"
            "reconnecting" -> "Восстанавливаем…"
            "error" -> "Нет соединения"
            else -> "VPN выключен"
        }
        detail.text = prefs.getString("state", "Не подключено")
        connectButton.text = if (phase == "disconnected") "◈  Подключить" else "Отключить VPN"
        connectButton.setTextColor(if (phase == "disconnected") navy else white)
        connectButton.background = shape(if (phase == "disconnected") mint else raised, 13)
        up.text = bytes(prefs.getLong("tx", 0))
        down.text = bytes(prefs.getLong("rx", 0))
        dropped.text = prefs.getLong("dropped", 0).toString()
        val since = prefs.getLong("since_elapsed", 0)
        val seconds = if (connected && since > 0)
            ((SystemClock.elapsedRealtime() - since).coerceAtLeast(0) / 1000) else 0L
        uptime.text = String.format(Locale.US, "%02d:%02d:%02d",
            seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    private fun updateProfile() {
        try {
            if (!SecretStore.exists(this)) {
                profile.text = "Профиль не загружен"
                endpoint.text = "Сервер: —"
                split.isChecked = true
                dns.setText("1.1.1.1")
                return
            }
            val config = VpnProfile.parse(SecretStore.load(this))
            val settings = ClientSettings.load(this, config)
            profile.text = "Клиент: " + config.localIp
            endpoint.text = "Сервер: " + config.endpointHost + ":" + config.endpointPort
            full.isChecked = settings.fullTunnel
            split.isChecked = !settings.fullTunnel
            dns.setText(settings.dns)
        } catch (e: Exception) {
            profile.text = "Ошибка профиля"
            endpoint.text = "Импортируйте конфигурацию снова"
        }
    }

    private fun problem(title: String, e: Exception) {
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(e.message ?: "Неизвестная ошибка")
            .setPositiveButton("Понятно", null).show()
    }

    private fun paste() {
        val edit = EditText(this).apply {
            minLines = 7
            maxLines = 12
            isSingleLine = false
            textSize = 13f
            setTextColor(white)
            hint = "[Interface]\nPrivateKey = ...\nAddress = ...\n[Peer]\nPublicKey = ..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Вставить конфигурацию")
            .setView(wrapper)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    SecretStore.save(this, edit.text.toString())
                    edit.text.clear()
                    updateProfile()
                    dialog.dismiss()
                    Toast.makeText(this, "Профиль импортирован", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { problem("Не удалось импортировать", e) }
            }
        }
        dialog.show()
    }

    private fun deleteProfile() {
        if (!SecretStore.exists(this)) return
        if (phase != "disconnected") {
            Toast.makeText(this, "Сначала отключите VPN", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить профиль?")
            .setMessage("Приватный ключ и конфигурация будут удалены с устройства.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                SecretStore.clear(this)
                updateProfile()
            }.show()
    }

    private fun connect() {
        try {
            if (!SecretStore.exists(this)) {
                Toast.makeText(this, "Импортируйте профиль", Toast.LENGTH_LONG).show()
                return
            }
            VpnProfile.parse(SecretStore.load(this))
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, vpnRequest)
            else startTunnel()
        } catch (e: Exception) { problem("Не удалось подключиться", e) }
    }

    private fun startTunnel() {
        val intent = Intent(this, NetherVpnService::class.java)
            .setAction(NetherVpnService.CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    private fun disconnect() {
        startService(Intent(this, NetherVpnService::class.java)
            .setAction(NetherVpnService.DISCONNECT))
    }

    @Deprecated("Android VPN consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequest) {
            if (resultCode == RESULT_OK) startTunnel()
            else Toast.makeText(this, "Нет разрешения на VPN", Toast.LENGTH_LONG).show()
        }
        if (requestCode == fileRequest && resultCode == RESULT_OK && data?.data != null) {
            try {
                val contents = contentResolver.openInputStream(data.data!!)!!.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        if (out.size() + size > 65536)
                            throw IllegalArgumentException("Файл слишком большой")
                        out.write(buffer, 0, size)
                    }
                    out.toByteArray()
                }
                SecretStore.save(this, String(contents, StandardCharsets.UTF_8))
                updateProfile()
                Toast.makeText(this, "Профиль импортирован", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { problem("Не удалось импортировать", e) }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updater)
    }
    override fun onPause() {
        handler.removeCallbacks(updater)
        super.onPause()
    }
}
