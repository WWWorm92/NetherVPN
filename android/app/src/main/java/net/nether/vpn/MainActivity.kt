package net.nether.vpn

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Diagnostic prototype; no VpnService tunnel is established. */
class MainActivity : Activity() {
 private val io = Executors.newSingleThreadExecutor()
 private lateinit var status: TextView
 private lateinit var endpoint: EditText
 private lateinit var profile: EditText
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("nether_test", MODE_PRIVATE)
  val panel = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(42, 64, 42, 24)
   setBackgroundColor(Color.rgb(16,24,39))
  }
  fun text(content: String, size: Float): TextView = TextView(this).apply {
   text=content; textSize=size; setTextColor(Color.WHITE)
  }
  panel.addView(text("◈  NetherVPN", 26f))
  panel.addView(text("Android · тестовая версия", 14f))
  panel.addView(text("Сервер (хост:порт)", 16f))
  endpoint = EditText(this).apply {
   setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
   hint="45.151.69.184:51830"
   setText(prefs.getString("endpoint","45.151.69.184:51830"))
  }
  panel.addView(endpoint)
  panel.addView(text("Конфигурация для проверки (без PrivateKey)", 14f))
  profile = EditText(this).apply {
   minLines=4; maxLines=8; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
   hint="[Interface] ... [Peer] ..."
   setText(prefs.getString("profile",""))
  }
  panel.addView(profile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 350))
  val save=Button(this).apply { text="Сохранить параметры" }
  save.setOnClickListener {
   val safe=profile.text.toString().lineSequence()
     .filterNot { it.trim().startsWith("PrivateKey",true) }.joinToString("\n")
   prefs.edit().putString("endpoint",endpoint.text.toString().trim()).putString("profile",safe).apply()
   profile.setText(safe)
   status.text="Параметры сохранены без приватного ключа"
  }
  panel.addView(save)
  val test=Button(this).apply { text="Проверить DNS и сеть" }
  panel.addView(test)
  status=text("VPN не подключён. Это диагностический прототип.",15f)
  panel.addView(status)
  test.setOnClickListener {
   val host=endpoint.text.toString().trim().substringBeforeLast(":")
   val port=endpoint.text.toString().trim().substringAfterLast(":").toIntOrNull()
   if(port==null || port !in 1..65535 || host.isEmpty()) {
    status.text="Неверный адрес"; return@setOnClickListener
   }
   status.text="Проверка DNS…"
   io.execute {
    val result=try {
     val addresses=java.net.InetAddress.getAllByName(host)
     "DNS: "+addresses.joinToString { it.hostAddress ?: "?" }+
      "\nUDP-доступность и Noise handshake НЕ проверены."
    } catch(e:Exception) { "Сеть/DNS: "+e.javaClass.simpleName }
    runOnUiThread {status.text=result}
   }
  }
  val note=text("⚠ Нет VpnService и Noise IK. Это НЕ VPN-подключение.\nНе вводите секретный ключ в тестовое приложение.",13f)
  panel.addView(note)
  val scroll=ScrollView(this); scroll.addView(panel); setContentView(scroll)
 }
 override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
