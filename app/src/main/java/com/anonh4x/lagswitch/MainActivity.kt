package com.anonh4x.lagswitch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var disconnect: Button
    private lateinit var testButton: Button
    private lateinit var testStatus: TextView
    private var vpnActive = false
    private var lagEnabled = false
    private var testRunning = false
    private var statusMessage = "Tünel kapalı"
    private var receiverRegistered = false
    private val requestVpn = 41

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LagVpnService.ACTION_STATE) return
            vpnActive = intent.getBooleanExtra(LagVpnService.EXTRA_VPN_ACTIVE, false)
            lagEnabled = intent.getBooleanExtra(LagVpnService.EXTRA_DELAY_ENABLED, false)
            statusMessage = intent.getStringExtra(LagVpnService.EXTRA_STATUS) ?: "Durum bilinmiyor"
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(LagVpnService.PREFS, MODE_PRIVATE)
        vpnActive = prefs.getBoolean(LagVpnService.KEY_VPN_ACTIVE, false)
        lagEnabled = vpnActive && prefs.getBoolean(LagVpnService.KEY_DELAY_ENABLED, false)
        statusMessage = prefs.getString(LagVpnService.KEY_STATUS, "Tünel kapalı") ?: "Tünel kapalı"
        val filter = IntentFilter(LagVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 48, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(13, 17, 23))
        }
        val title = TextView(this).apply {
            text = "ANONH4X  /  LAG SWITCH"
            textSize = 23f
            setTextColor(Color.rgb(0, 230, 170))
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 28, 0, 20) }
        toggle = Button(this).apply { textSize = 18f; setOnClickListener { onToggle() } }
        disconnect = Button(this).apply { text = "VPN'İ KAPAT"; setOnClickListener { disconnectVpn() } }
        testButton = Button(this).apply { text = "BAĞLANTIYI TEST ET"; setOnClickListener { runConnectivityTest() } }
        testStatus = TextView(this).apply {
            text = "Test, uygulamanın gerçek VPN yolundan HTTPS isteği gönderir."
            textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 14, 0, 12)
        }
        val info = TextView(this).apply {
            text = "Gecikme hedefi: 1000 ms her yön.\n\nYalnızca Oxide ve bu uygulamanın bağlantı testi tünelden geçer; diğer uygulamalar etkilenmez. DNS sorguları gecikmesiz bırakılır.\n\nLAG SWITCH • OFF gecikmeyi kapatır ama VPN'i açık tutar. Tamamen çıkmak için VPN'İ KAPAT düğmesini kullan. Oyunun sunucusu zaman aşımına uğrayabilir."
            textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 22, 0, 0)
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        root.addView(disconnect, LinearLayout.LayoutParams(-1, -2))
        root.addView(testButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(testStatus, ViewGroup.LayoutParams(-1, -2))
        root.addView(info, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun onToggle() {
        if (vpnActive) {
            val next = !lagEnabled
            statusMessage = if (next) "Gecikme açılıyor…" else "Gecikme kapatılıyor…"
            refresh()
            startService(Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_SET_DELAY)
                .putExtra(LagVpnService.EXTRA_DELAY_ENABLED, next))
            return
        }
        statusMessage = "VPN tüneli başlatılıyor…"
        refresh()
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, requestVpn) else startLagVpnService()
    }

    private fun startLagVpnService() {
        val intent = Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun disconnectVpn() {
        if (!vpnActive) return
        statusMessage = "VPN kapatılıyor…"
        refresh()
        startService(Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_STOP))
    }

    private fun runConnectivityTest() {
        if (!vpnActive || testRunning) return
        testRunning = true
        testStatus.text = "Test ediliyor… Gecikme açıksa sonuç uzayabilir."
        refresh()
        Thread {
            val startedAt = System.nanoTime()
            var connection: HttpURLConnection? = null
            val result = try {
                connection = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
                connection!!.instanceFollowRedirects = false
                connection!!.connectTimeout = 30_000
                connection!!.readTimeout = 30_000
                connection!!.requestMethod = "GET"
                val code = connection!!.responseCode
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                if (code == 204) "BAŞARILI • HTTPS 204 • ${elapsedMs} ms"
                else "YANIT ALINDI • HTTP ${code} • ${elapsedMs} ms (204 bekleniyordu)"
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                "BAŞARISIZ • ${e.javaClass.simpleName}: ${e.message ?: "bağlantı hatası"} • ${elapsedMs} ms"
            } finally { connection?.disconnect() }
            runOnUiThread {
                testStatus.text = result
                testRunning = false
                refresh()
            }
        }.apply { name = "LagSwitch-ConnectivityTest"; isDaemon = true }.start()
    }

    @Deprecated("Deprecated API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVpn) {
            if (resultCode == RESULT_OK) startLagVpnService()
            else { statusMessage = "VPN izni verilmedi"; refresh() }
        }
    }

    private fun refresh() {
        status.text = "DURUM: ${statusMessage}"
        toggle.text = if (lagEnabled) "LAG SWITCH  •  OFF" else "LAG SWITCH  •  ON"
        disconnect.isEnabled = vpnActive
        testButton.isEnabled = vpnActive && !testRunning
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(stateReceiver)
        super.onDestroy()
    }
}
