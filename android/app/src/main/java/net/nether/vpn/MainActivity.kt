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
    private val navy = Color.rgb(16, 27, 41)
    private val card = Color.rgb(25, 43, 62)
    private val raised = Color.rgb(35, 59, 82)
    private val mint = Color.rgb(118, 217, 199)
    private val white = Color.rgb(237, 244, 250)
    private val muted = Color.rgb(158, 176, 196)
    private val amber = Color.rgb(244, 197, 127)
    private val red = Color.rgb(255, 135, 156)
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
    private var renderedPhase = ""

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
    private fun space(parent: LinearLayout, size: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }
    private val updater = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var modeTitle: TextView


    private fun connectionSurface(color: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(color, Color.rgb(33, 98, 107))
    ).apply { cornerRadius = dp(30).toFloat() }

    private fun powerRing(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(17, 39, 55))
        setStroke(dp(4), color)
    }

    private fun softCard(color: Int = card, radius: Int = 28) =
        shape(color, radius)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy

        // Compact, native, vertically scrollable dashboard without fake navigation tabs.
        val root = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(navy)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(25), dp(22), dp(24))
        }
        root.addView(content)

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nethervpn_foreground)
            background = shape(raised, 16)
            contentDescription = "NetherVPN"
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            rightMargin = dp(12)
        })
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        branding.addView(text("NetherVPN", 22f, white, true))
        branding.addView(text("Приватная сеть · Ваш сервер", 11f, muted))
        heading.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("ALPHA", 10f, mint, true).apply {
            background = shape(raised, 14)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        })
        content.addView(heading)
        space(content, 32)

        content.addView(text("ЗАЩИТА СОЕДИНЕНИЯ", 11f, muted, true))
        space(content, 16)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(5), dp(8), dp(10))
        }
        badge = text("●  НЕ ПОДКЛЮЧЕНО", 12f, muted, true)
        status = text("VPN выключен", 18f, white, true)
        detail = text("Подключитесь к своему серверу", 12f, muted).apply {
            gravity = Gravity.CENTER
            maxLines = 3
        }
        // Connection ring follows the approved visual concept; the ring's
        // caption comes from VpnService state, never an assumed handshake.
        connectButton = Button(this).apply {
            isAllCaps = false
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(white)
            background = powerRing(muted)
            backgroundTintList = null
            elevation = dp(3).toFloat()
            contentDescription = "Управление VPN-соединением"
            setOnClickListener {
                if (phase == "disconnected") connect() else disconnect()
            }
        }
        hero.addView(connectButton, LinearLayout.LayoutParams(dp(205), dp(205)))
        space(hero, 12)
        hero.addView(badge)
        space(hero, 7)
        hero.addView(status)
        space(hero, 5)
        hero.addView(detail)
        content.addView(hero)
        space(content, 17)

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun metricTile(caption: String, value: TextView): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = softCard(card, 24)
                setPadding(dp(17), dp(17), dp(12), dp(17))
                addView(text(caption, 10f, muted, true))
                space(this, 7)
                addView(value)
            }
        down = text("0 Б", 18f, white, true)
        up = text("0 Б", 18f, white, true)
        dropped = text("0", 12f, muted)
        uptime = text("00:00:00", 12f, muted)
        metrics.addView(metricTile("↓  ПОЛУЧЕНО", down),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(7) })
        metrics.addView(metricTile("↑  ОТПРАВЛЕНО", up),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        content.addView(metrics)
        val diagnostics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(15), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = "Показать подробную статистику"
            setOnClickListener { statisticsDialog() }
        }
        diagnostics.addView(text("Сессия", 12f, muted))
        diagnostics.addView(uptime, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(8)
        })
        diagnostics.addView(text("Статистика  ↗", 12f, mint, true))
        content.addView(diagnostics)
        space(content, 20)

        content.addView(text("ПОДКЛЮЧЕНИЕ", 11f, muted, true))
        space(content, 10)
        val server = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = softCard(card, 27)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { profileDialog() }
        }
        server.addView(text("ВАШ ПРОФИЛЬ     ›", 10f, muted, true))
        space(server, 8)
        profile = text("Добавить сервер", 19f, white, true)
        server.addView(profile)
        space(server, 5)
        endpoint = text("Конфигурация не загружена", 12f, muted)
        server.addView(endpoint)
        content.addView(server)
        space(content, 12)

        modeTitle = text("Режим подключения   ›", 13f, mint).apply {
            background = softCard(raised, 22)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { settingsDialog() }
        }
        content.addView(modeTitle, LinearLayout.LayoutParams(-1, -2))
        space(content, 18)

        // Genuine navigation actions, not inert decorative tabs.
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = softCard(card, 25)
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
        fun tab(caption: String, selected: Boolean = false, action: () -> Unit) =
            TextView(this).apply {
                text = caption
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(if (selected) mint else muted)
                background = if (selected) softCard(raised, 18) else softCard(card, 18)
                setPadding(dp(4), dp(14), dp(4), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        navigation.addView(tab("⌂  Главная", true) {
            root.smoothScrollTo(0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(tab("▤  Серверы") { profileDialog() },
            LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(tab("▣  Профили") { profileDialog() },
            LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(tab("⚙  Настройки") { settingsDialog() },
            LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(navigation)
        space(content, 14)
        content.addView(text("Noise IK · IPv4 · Экспериментальный VPN",
            10f, muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))

        full = RadioButton(this)
        split = RadioButton(this)
        dns = EditText(this)
        setContentView(root)
        updateProfile()
        updateStatus()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 55)
        }
    }

    private fun importDocument() {
        if (phase != "disconnected") {
            Toast.makeText(this, "Сначала отключите VPN", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, fileRequest)
    }

    private fun profileDialog() {
        val imported = SecretStore.exists(this)
        val actions = if (imported)
            arrayOf("Импортировать новый .conf", "Вставить конфигурацию", "Удалить профиль")
        else arrayOf("Импортировать .conf", "Вставить конфигурацию")
        AlertDialog.Builder(this)
            .setTitle("Профиль сервера")
            .setItems(actions) { _, selected ->
                when (selected) {
                    0 -> importDocument()
                    1 -> paste()
                    2 -> deleteProfile()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun settingsDialog() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(3))
        }
        holder.addView(text("КАК НАПРАВЛЯТЬ ТРАФИК", 11f, muted, true))
        space(holder, 11)
        val routes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        full = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Весь IPv4 через VPN"
            setTextColor(white)
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(amber)
        }
        split = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Только VPN-сервер"
            setTextColor(white)
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(amber)
        }
        routes.addView(full)
        routes.addView(split)
        holder.addView(routes)
        space(holder, 12)
        holder.addView(text("DNS для полного туннеля", 12f, muted))
        space(holder, 7)
        dns = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "1.1.1.1"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            background = shape(raised, 10)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        holder.addView(dns, LinearLayout.LayoutParams(-1, dp(48)))
        space(holder, 12)
        holder.addView(text(
            "В Full Tunnel IPv6 пока блокируется. Для выхода в интернет серверу нужен NAT.",
            12f, muted))
        updateProfile()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(holder)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .setNeutralButton("Постоянная VPN") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    ClientSettings.save(this, full.isChecked, dns.text.toString())
                    updateProfile()
                    dialog.dismiss()
                    Toast.makeText(this,
                        "Сохранено. Переподключите VPN для применения.",
                        Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    problem("Настройки", e)
                }
            }
        }
        dialog.show()
    }

    private fun statisticsDialog() {
        val info = "↑ Отправлено: " + up.text + "\n" +
            "↓ Получено: " + down.text + "\n" +
            "Время: " + uptime.text + "\n" +
            "Отброшено пакетов: " + dropped.text + "\n\n" +
            "Noise IK · IPv4-only · экспериментальная версия"
        AlertDialog.Builder(this)
            .setTitle("Статистика и диагностика")
            .setMessage(info)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("VPN в Android") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
            }
            .show()
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
        // A process kill may leave the last SharedPreferences state as
        // "connected". Never show a stale green security indication.
        val last = prefs.getLong("updated_elapsed", 0L)
        val now = SystemClock.elapsedRealtime()
        if (phase == "connected" && (last == 0L || now < last || now - last > 30000L))
            phase = "stale"
        val connected = phase == "connected"
        val pending = phase == "connecting" || phase == "reconnecting"
        badge.setTextColor(if (connected) mint else if (pending || phase == "stale") amber
                           else if (phase == "error") red else muted)
        badge.text = when (phase) {
            "connected" -> "●  СОЕДИНЕНИЕ ЗАЩИЩЕНО"
            "connecting" -> "●  ПОДКЛЮЧЕНИЕ"
            "reconnecting" -> "●  ПЕРЕПОДКЛЮЧЕНИЕ"
            "stale" -> "●  НЕТ СВЕЖЕГО СОСТОЯНИЯ"
            "error" -> "●  ОШИБКА"
            else -> "●  НЕ ПОДКЛЮЧЕНО"
        }
        status.text = when (phase) {
            "connected" -> "Подключено"
            "connecting" -> "Подключаемся…"
            "reconnecting" -> "Восстанавливаем…"
            "stale" -> "Проверьте соединение"
            "error" -> "Нет соединения"
            else -> "VPN выключен"
        }
        detail.text = if (phase == "stale")
            "Нет свежего подтверждения от VPN-службы. Проверьте настройки."
            else if (phase == "error" || phase == "reconnecting")
            prefs.getString("last_error", prefs.getString("state", "Нет соединения"))
            else prefs.getString("state", "Не подключено")
        connectButton.text = when (phase) {
            "connected" -> "Отключить VPN"
            "connecting" -> "Отменить подключение"
            "reconnecting" -> "Остановить попытки"
            "stale" -> "Сбросить VPN"
            "error" -> "Сбросить VPN"
            else -> "Подключить"
        }
        connectButton.setTextColor(white)
        if (renderedPhase != phase) {
            connectButton.background = powerRing(
                if (connected) mint else if (pending) amber else if (phase == "error") red else muted)
            renderedPhase = phase
        }
        connectButton.contentDescription = if (phase == "disconnected")
            "Подключить VPN" else "Отключить VPN"
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
                profile.text = "Добавить сервер   ＋"
                endpoint.text = "Сервер: —"
                split.isChecked = true
                dns.setText("1.1.1.1")
                modeTitle.text = "Режим подключения   ⌄"
                return
            }
            val config = VpnProfile.parse(SecretStore.load(this))
            val settings = ClientSettings.load(this, config)
            profile.text = "NetherVPN  ⌄"
            endpoint.text = config.endpointHost + ":" + config.endpointPort
            full.isChecked = settings.fullTunnel
            split.isChecked = !settings.fullTunnel
            dns.setText(settings.dns)
            modeTitle.text = if (settings.fullTunnel) "Весь IPv4 через VPN   ⌄" else "Только VPN-сервер   ⌄"
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
        if (phase != "disconnected") {
            Toast.makeText(this, "Сначала отключите VPN", Toast.LENGTH_LONG).show()
            return
        }
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
