package net.nether.vpn

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

private val Night = Color(0xFF081626)
private val Navy = Color(0xFF0D2033)
private val Surface = Color(0xFF152B41)
private val Raised = Color(0xFF1B384E)
private val Teal = Color(0xFF36E6CA)
private val Pale = Color(0xFFF1FAFF)
private val Secondary = Color(0xFFA4BDD0)
private val Warning = Color(0xFFF6CA83)
private val Danger = Color(0xFFFF788B)

private data class VpnUi(
    val phase: String = "disconnected",
    val detail: String = "Импортируйте профиль, чтобы начать",
    val up: Long = 0,
    val down: Long = 0,
    val dropped: Long = 0,
    val elapsed: Long = 0,
    val stage: String = "idle",
    val failedStage: String = "",
    val lastError: String = "",
    val wireRx: Long = 0,
    val decryptedRx: Long = 0,
    val writtenRx: Long = 0,
    val invalidRx: Long = 0,
    val outboundDrops: Long = 0,
    val inboundDrops: Long = 0,
    val ipv6Drops: Long = 0
) {
    val active get() = phase == "connected"
    val pending get() = phase == "connecting" || phase == "reconnecting"
}

private data class ProfileUi(
    val available: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val localIp: String = "",
    val full: Boolean = false,
    val dns: String = "1.1.1.1",
    val error: String = ""
)

class MainActivity : ComponentActivity() {
    private var vpn by mutableStateOf(VpnUi())
    private var profile by mutableStateOf(ProfileUi())
    private var currentTab by mutableIntStateOf(0)
    private var modal by mutableStateOf("")
    private var alert by mutableStateOf("")

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 65536) error("Файл профиля слишком большой")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Не удалось открыть файл")
                SecretStore.save(this, String(bytes, StandardCharsets.UTF_8))
                reloadProfile()
                toast("Профиль импортирован")
            } catch (e: Exception) {
                alert = "Импорт: " + (e.message ?: "Ошибка чтения профиля")
            }
        }
    }

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else toast("Разрешение на VPN не предоставлено")
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* VPN service itself works even if notifications are denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 22, 38)
        window.navigationBarColor = android.graphics.Color.rgb(8, 22, 38)
        reloadProfile()
        refreshStatus()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Teal, onPrimary = Night, background = Night,
                surface = Surface, onSurface = Pale, secondary = Secondary,
                error = Danger
            )) {
                LaunchedEffect(Unit) {
                    while (true) {
                        refreshStatus()
                        delay(1000)
                    }
                }
                NetherScreen(
                    vpn = vpn,
                    profile = profile,
                    selected = currentTab,
                    onTab = { currentTab = it },
                    onPower = { toggleVpn() },
                    onImport = { if (canEditProfile()) importPicker.launch(arrayOf("*/*")) },
                    onPaste = { if (canEditProfile()) modal = "paste" },
                    onDelete = { if (canEditProfile()) modal = "delete" },
                    onSave = { full, dns -> saveOptions(full, dns) },
                    onSystemVpn = { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                    onCopyDiagnostics = { copyDiagnostics() }
                )
                if (modal == "paste") PasteDialog(
                    onDismiss = { modal = "" },
                    onSave = { raw ->
                        try {
                            SecretStore.save(this, raw)
                            reloadProfile()
                            modal = ""
                            toast("Профиль импортирован")
                        } catch (e: Exception) {
                            alert = "Профиль: " + (e.message ?: "Неверная конфигурация")
                        }
                    }
                )
                if (modal == "delete") AlertDialog(
                    onDismissRequest = { modal = "" },
                    title = { Text("Удалить профиль?") },
                    text = { Text("Конфигурация и приватный ключ будут удалены с телефона.") },
                    confirmButton = {
                        TextButton(onClick = {
                            SecretStore.clear(this)
                            reloadProfile()
                            modal = ""
                        }) { Text("Удалить", color = Danger) }
                    },
                    dismissButton = { TextButton(onClick = { modal = "" }) { Text("Отмена") } }
                )
                if (alert.isNotEmpty()) AlertDialog(
                    onDismissRequest = { alert = "" },
                    title = { Text("NetherVPN") },
                    text = { Text(alert) },
                    confirmButton = { TextButton(onClick = { alert = "" }) { Text("Понятно") } }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun reloadProfile() {
        profile = try {
            if (!SecretStore.exists(this)) ProfileUi()
            else {
                val conf = VpnProfile.parse(SecretStore.load(this))
                val local = ClientSettings.load(this, conf)
                ProfileUi(true, conf.endpointHost, conf.endpointPort, conf.localIp,
                    local.fullTunnel, local.dns)
            }
        } catch (e: Exception) {
            ProfileUi(error = e.message ?: "Не удалось прочитать профиль")
        }
    }

    private fun refreshStatus() {
        val p = getSharedPreferences(NetherVpnService.PREFS, MODE_PRIVATE)
        var phase = p.getString("phase", "disconnected") ?: "disconnected"
        val now = SystemClock.elapsedRealtime()
        val updated = p.getLong("updated_elapsed", 0)
        if (phase == "connected" && (updated <= 0 || updated > now || now - updated > 30000)) {
            phase = "stale"
        }
        val since = p.getLong("since_elapsed", 0L)
        val secs = if (phase == "connected" && since > 0 && now >= since)
            (now - since) / 1000 else 0
        val detail = if (phase == "stale") "Нет свежего подтверждения от службы"
            else if (phase == "error" || phase == "reconnecting")
                p.getString("last_error", null)?.takeIf { it.isNotBlank() }
                    ?: p.getString("state", "Нет соединения").orEmpty()
            else p.getString("state", "Не подключено").orEmpty()
        vpn = VpnUi(phase, detail, p.getLong("tx", 0), p.getLong("rx", 0),
            p.getLong("dropped", 0), secs,
            p.getString("stage", "idle") ?: "idle",
            p.getString("failed_stage", "") ?: "",
            p.getString("last_error", "") ?: "",
            p.getLong("wire_rx", 0), p.getLong("decrypted_rx", 0),
            p.getLong("written_rx", 0), p.getLong("invalid_rx", 0),
            p.getLong("outbound_drops", 0), p.getLong("inbound_drops", 0),
            p.getLong("ipv6_drops", 0))
    }

    private fun canEditProfile(): Boolean {
        if (vpn.phase == "disconnected") return true
        toast("Сначала отключите VPN")
        return false
    }

    private fun toggleVpn() {
        if (vpn.phase != "disconnected") {
            startService(Intent(this, NetherVpnService::class.java)
                .setAction(NetherVpnService.DISCONNECT))
            return
        }
        try {
            if (!profile.available) error("Импортируйте отдельный профиль для Android")
            VpnProfile.parse(SecretStore.load(this))
            val intent = VpnService.prepare(this)
            if (intent != null) vpnConsent.launch(intent) else startVpn()
        } catch (e: Exception) {
            alert = e.message ?: "Ошибка подключения"
        }
    }

    private fun startVpn() {
        try {
            val intent = Intent(this, NetherVpnService::class.java)
                .setAction(NetherVpnService.CONNECT)
            startForegroundService(intent)
        } catch (e: Exception) {
            alert = "Запуск VPN: " + (e.message ?: "Ошибка запуска службы")
        }
    }

    private fun saveOptions(full: Boolean, dns: String): Boolean {
        return try {
            ClientSettings.save(this, full, dns)
            reloadProfile()
            toast(if (vpn.phase == "disconnected") "Настройки сохранены"
                else "Настройки сохранены — переподключите VPN")
            true
        } catch (e: Exception) {
            alert = "Настройки: " + (e.message ?: "Ошибка сохранения")
            false
        }
    }

    private fun copyDiagnostics() {
        val safe = buildString {
            appendLine("NetherVPN Android diagnostic")
            appendLine("phase=${vpn.phase}")
            appendLine("stage=${vpn.stage}")
            appendLine("failed_stage=${vpn.failedStage}")
            appendLine("error=${vpn.lastError}")
            appendLine("detail=${vpn.detail}")
            appendLine("rx=${vpn.down} tx=${vpn.up} dropped=${vpn.dropped}")
            appendLine("rx_udp_packets=${vpn.wireRx} rx_decrypted_packets=${vpn.decryptedRx}")
            appendLine("rx_tun_packets=${vpn.writtenRx} rx_crypto_rejects=${vpn.invalidRx}")
            appendLine("drops_outbound=${vpn.outboundDrops} drops_inbound=${vpn.inboundDrops}")
            appendLine("drops_ipv6=${vpn.ipv6Drops}")
            appendLine("profile_imported=${profile.available}")
            appendLine("full_tunnel=${profile.full}")
            // Never include private keys, server public keys, or raw .conf.
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NetherVPN diagnostic", safe))
        toast("Диагностика скопирована без секретных ключей")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f КиБ", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f МиБ", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f ГиБ", bytes / 1073741824.0)
}

private fun elapsedText(s: Long): String =
    String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)

@Composable
private fun NetherScreen(
    vpn: VpnUi,
    profile: ProfileUi,
    selected: Int,
    onTab: (Int) -> Unit,
    onPower: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Boolean, String) -> Boolean,
    onSystemVpn: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Night, Navy, Night))
    )) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { BottomTabs(selected, onTab) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(6.dp))
                BrandHeader()
                when (selected) {
                    0 -> HomePage(vpn, profile, onPower, { onTab(1) }, { onTab(2) })
                    1 -> ProfilesPage(vpn, profile, onImport, onPaste, onDelete)
                    else -> SettingsPage(profile, vpn, onSave, onSystemVpn, onCopyDiagnostics)
                }
                Spacer(Modifier.height(18.dp))
                Text("Noise IK  ·  IPv4  ·  Экспериментальная версия",
                    modifier = Modifier.fillMaxWidth(), color = Secondary,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Teal.copy(alpha = .13f))
            .border(1.dp, Teal.copy(alpha = .65f),
                androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Text("N", fontSize = 26.sp, color = Teal, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Text("NetherVPN", fontWeight = FontWeight.Bold, fontSize = 21.sp,
            color = Pale, modifier = Modifier.weight(1f))
        Text("ALPHA", fontSize = 10.sp, color = Teal,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Raised).padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun HomePage(
    vpn: VpnUi,
    profile: ProfileUi,
    onPower: () -> Unit,
    onServer: () -> Unit,
    onSettings: () -> Unit
) {
    val statusText = when (vpn.phase) {
        "connected" -> "Туннель активен"
        "connecting" -> "Подключение…"
        "reconnecting" -> "Восстановление…"
        "error" -> "Ошибка подключения"
        "stale" -> "Статус неизвестен"
        else -> "Не подключено"
    }
    val accent = when {
        vpn.active -> Teal
        vpn.pending -> Warning
        vpn.phase == "error" -> Danger
        else -> Secondary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))
        PowerOrb(vpn.phase, statusText, accent, onPower)
        Spacer(Modifier.height(7.dp))
        Text(vpn.detail, color = Secondary, fontSize = 12.sp,
            lineHeight = 17.sp, textAlign = TextAlign.Center,
            maxLines = 3, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(5.dp))
        Text("◷  " + elapsedText(vpn.elapsed), color = Pale,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Metric("↓  ПОЛУЧЕНО", humanBytes(vpn.down), Teal, Modifier.weight(1f))
        Metric("↑  ОТПРАВЛЕНО", humanBytes(vpn.up), Color(0xFFF6AADD), Modifier.weight(1f))
    }
    ClickCard("СЕРВЕР", if (profile.available) profile.host else "Добавить сервер",
        if (profile.available) "${profile.host}:${profile.port}" else "Импортируйте .conf", onServer)
    ClickCard("ТУННЕЛЬ", if (profile.full) "Полный туннель" else "Раздельный",
        if (profile.full) "Весь IPv4 через VPN · DNS ${profile.dns}"
        else "Только адрес VPN-сервера", onSettings)
    if (vpn.dropped > 0L) {
        Text("Отброшено пакетов: ${vpn.dropped}", color = Warning, fontSize = 12.sp)
    }
    if (vpn.phase != "disconnected") {
        OutlinedButton(onClick = onPower, modifier = Modifier.fillMaxWidth()) {
            Text("Отключить / остановить VPN")
        }
    }
}

@Composable
private fun PowerOrb(phase: String, title: String, color: Color, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "orbPulse")
    val halo by pulse.animateFloat(initialValue = .08f, targetValue = .23f,
        animationSpec = infiniteRepeatable(tween(1900), RepeatMode.Reverse),
        label = "halo")
    val arc by animateFloatAsState(
        targetValue = when (phase) {
            "connected" -> 310f
            "connecting", "reconnecting" -> 185f
            else -> 55f
        }, animationSpec = tween(550), label = "connectionArc")
    val animatedColor by animateColorAsState(color, label = "connectionColor")
    Box(Modifier.size(225.dp)
        .semantics { contentDescription = "VPN: $title. Нажмите для управления соединением" }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .38f
            drawCircle(animatedColor.copy(alpha = halo), radius * 1.19f,
                style = Stroke(width = 15.dp.toPx()))
            drawCircle(Color(0xFF15394C), radius = radius)
            drawCircle(Color(0xFF28576A), radius = radius,
                style = Stroke(width = 3.dp.toPx()))
            drawArc(animatedColor, startAngle = -95f, sweepAngle = arc,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏻", fontSize = 42.sp, color = animatedColor, lineHeight = 49.sp)
            Spacer(Modifier.height(3.dp))
            Text(if (phase == "connected") "Отключить"
                 else if (phase == "disconnected") "Подключить"
                 else if (phase == "error" || phase == "stale") "Остановить"
                 else "Отмена",
                color = Pale, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = Secondary, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Metric(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(9.dp))
            Text(value, color = Pale, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ClickCard(eyebrow: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(eyebrow, color = Secondary, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(title, color = Pale, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Secondary, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Teal, fontSize = 29.sp)
        }
    }
}

@Composable
private fun ProfilesPage(vpn: VpnUi, profile: ProfileUi,
                         onImport: () -> Unit, onPaste: () -> Unit, onDelete: () -> Unit) {
    Heading("Профили", "Безопасный импорт конфигурации")
    ClickCard("ПРОФИЛЬ НА УСТРОЙСТВЕ",
        if (profile.available) "NetherVPN Android" else "Профиль отсутствует",
        if (profile.available) "Адрес: ${profile.localIp}"
        else "Создайте отдельный профиль через nether-admin", onImport)
    if (profile.error.isNotEmpty()) Text(profile.error, color = Danger)
    Button(onClick = onImport, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Night)) {
        Text("＋  Импортировать .conf", modifier = Modifier.padding(7.dp))
    }
    OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
        Text("Вставить конфигурацию")
    }
    if (profile.available) TextButton(onClick = onDelete,
        modifier = Modifier.fillMaxWidth()) { Text("Удалить профиль", color = Danger) }
    Text("Сейчас поддерживается один сохранённый профиль на устройство. " +
        "Приватный ключ хранится в Android Keystore; на экране он не показывается.",
        color = Secondary, fontSize = 12.sp, lineHeight = 18.sp)
    if (vpn.phase != "disconnected")
        Text("Перед изменением профиля отключите VPN.", color = Warning)
}

@Composable
private fun SettingsPage(profile: ProfileUi, vpn: VpnUi,
                         onSave: (Boolean, String) -> Boolean, onSystemVpn: () -> Unit,
                         onCopyDiagnostics: () -> Unit) {
    Heading("Настройки", "Режим и DNS задаются на телефоне")
    var full by remember(profile.full, profile.host) { mutableStateOf(profile.full) }
    var dns by remember(profile.dns, profile.host) { mutableStateOf(profile.dns) }
    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("РЕЖИМ ТУННЕЛЯ", color = Secondary, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { full = true }) {
                RadioButton(selected = full, onClick = { full = true })
                Text("Весь IPv4 через VPN", color = Pale)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { full = false }) {
                RadioButton(selected = !full, onClick = { full = false })
                Text("Только VPN-сервер", color = Pale)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = dns, onValueChange = { dns = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("DNS (IPv4)") },
                singleLine = true)
        }
    }
    Button(onClick = { onSave(full, dns) }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Night)) {
        Text("Сохранить настройки", modifier = Modifier.padding(6.dp))
    }
    ClickCard("ДИАГНОСТИКА",
        "Этап: " + (if (vpn.failedStage.isNotBlank()) vpn.failedStage else vpn.stage),
        if (vpn.lastError.isNotBlank()) vpn.lastError else vpn.detail,
        onCopyDiagnostics)
    Text("Нажмите карточку диагностики, чтобы скопировать безопасный отчёт.",
        color = Secondary, fontSize = 11.sp)
    ClickCard("ANDROID", "Постоянное подключение",
        "Always-on и блокировка без VPN настраиваются в системе", onSystemVpn)
    Text("В полном туннеле IPv6 блокируется, а для выхода IPv4 в интернет " +
        "на сервере необходимы NAT и пересылка пакетов. " +
        if (vpn.phase != "disconnected") "Изменения вступят в силу после переподключения." else "",
        color = Secondary, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Pale, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Text(subtitle, color = Secondary, fontSize = 13.sp)
    }
}

@Composable
private fun BottomTabs(selected: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("⌂" to "Главная", "▣" to "Профили", "⚙" to "Настройки")
    Row(Modifier.fillMaxWidth()
        .background(Night.copy(alpha = .97f))
        .padding(horizontal = 10.dp, vertical = 8.dp)
        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { index, (symbol, name) ->
            val chosen = index == selected
            Column(Modifier.weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(19.dp))
                .background(if (chosen) Raised else Color.Transparent)
                .clickable { onTab(index) }
                .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(symbol, color = if (chosen) Teal else Secondary, fontSize = 22.sp)
                Text(name, color = if (chosen) Teal else Secondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var contents by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Вставить конфигурацию") },
        text = {
            OutlinedTextField(value = contents, onValueChange = {
                if (it.length <= 65536) contents = it
            }, minLines = 7, maxLines = 11, label = { Text("Содержимое .conf") },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(contents) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
