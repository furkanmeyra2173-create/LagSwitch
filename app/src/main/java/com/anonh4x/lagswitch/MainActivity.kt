package com.anonh4x.lagswitch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var disconnect: Button
    private lateinit var testButton: Button
    private lateinit var testStatus: TextView
    private lateinit var delayLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var onTimeLabel: TextView
    private lateinit var offTimeLabel: TextView
    private lateinit var autoCycle: CheckBox
    private lateinit var appSelectionStatus: TextView
    private val selectedApps = mutableSetOf<String>()
    private var vpnActive = false
    private var lagEnabled = false
    private var testRunning = false
    private var statusMessage = "Tünel kapalı"
    private var receiverRegistered = false
    private val requestVpn = 41
    private val requestOverlay = 42
    private val uiHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(LagVpnService.PREFS, MODE_PRIVATE) }
    private val cycleAction = Runnable {
        if (autoCycle.isChecked && vpnActive) {
            startService(Intent(this, LagVpnService::class.java)
                .setAction(LagVpnService.ACTION_SET_DELAY)
                .putExtra(LagVpnService.EXTRA_DELAY_ENABLED, !lagEnabled))
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LagVpnService.ACTION_STATE) return
            vpnActive = intent.getBooleanExtra(LagVpnService.EXTRA_VPN_ACTIVE, false)
            lagEnabled = intent.getBooleanExtra(LagVpnService.EXTRA_DELAY_ENABLED, false)
            statusMessage = intent.getStringExtra(LagVpnService.EXTRA_STATUS) ?: "Durum bilinmiyor"
            refresh()
            scheduleCycle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        loadSelectedApps()
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(10, 13, 20))
        }
        val title = TextView(this).apply {
            text = "ANONH4X"
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(0, 235, 170))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "FAKE LAG  /  NETWORK CONTROL"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(135, 151, 172))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(12))
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(23, 30, 42))
        }
        toggle = Button(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 160, 115))
            setOnClickListener { onToggle() }
        }
        disconnect = Button(this).apply {
            text = "VPN'İ TAMAMEN KAPAT"
            setOnClickListener { disconnectVpn() }
        }
        testButton = Button(this).apply {
            text = "BAĞLANTIYI TEST ET"
            setOnClickListener { runConnectivityTest() }
        }
        testStatus = TextView(this).apply {
            text = "Bağlantı testi, uygulamanın VPN üzerinden erişimini kontrol eder."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }

        val settingsTitle = TextView(this).apply {
            text = "AYARLAR"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(0, 235, 170))
            setPadding(0, dp(20), 0, dp(5))
        }
        delayLabel = label("Paket gecikmesi: ${prefs.getInt(LagVpnService.KEY_DELAY_MS, 1000)} ms")
        val delaySeek = SeekBar(this).apply {
            max = 3000
            progress = prefs.getInt(LagVpnService.KEY_DELAY_MS, 1000).coerceIn(0, 3000)
            setOnSeekBarChangeListener(seekListener { value ->
                prefs.edit().putInt(LagVpnService.KEY_DELAY_MS, value).apply()
                delayLabel.text = "Paket gecikmesi: $value ms"
            })
        }
        sizeLabel = label("Aç/Kapat düğmesi: ${prefs.getInt(KEY_BUTTON_SIZE, 64)} dp")
        val sizeSeek = SeekBar(this).apply {
            max = 56
            progress = (prefs.getInt(KEY_BUTTON_SIZE, 64) - 48).coerceIn(0, 56)
            setOnSeekBarChangeListener(seekListener { value ->
                val size = value + 48
                prefs.edit().putInt(KEY_BUTTON_SIZE, size).apply()
                sizeLabel.text = "Aç/Kapat düğmesi: $size dp"
                applyToggleSize(size)
            })
        }
        autoCycle = CheckBox(this).apply {
            text = "Otomatik aç/kapat döngüsü"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(KEY_AUTO_CYCLE, false)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 235, 170))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_AUTO_CYCLE, checked).apply()
                scheduleCycle()
            }
        }
        onTimeLabel = label("Gecikme AÇIK süresi: ${prefs.getInt(KEY_ON_SECONDS, 5)} sn")
        val onSeek = SeekBar(this).apply {
            max = 29
            progress = prefs.getInt(KEY_ON_SECONDS, 5).coerceIn(1, 30) - 1
            setOnSeekBarChangeListener(seekListener { value ->
                val seconds = value + 1
                prefs.edit().putInt(KEY_ON_SECONDS, seconds).apply()
                onTimeLabel.text = "Gecikme AÇIK süresi: $seconds sn"
                scheduleCycle()
            })
        }
        offTimeLabel = label("Gecikme KAPALI süresi: ${prefs.getInt(KEY_OFF_SECONDS, 5)} sn")
        val offSeek = SeekBar(this).apply {
            max = 29
            progress = prefs.getInt(KEY_OFF_SECONDS, 5).coerceIn(1, 30) - 1
            setOnSeekBarChangeListener(seekListener { value ->
                val seconds = value + 1
                prefs.edit().putInt(KEY_OFF_SECONDS, seconds).apply()
                offTimeLabel.text = "Gecikme KAPALI süresi: $seconds sn"
                scheduleCycle()
            })
        }
        val info = TextView(this).apply {
            text = "Gecikme ayarı 0–3000 ms arasındadır. VPN gecikme KAPALI başlar; Oxide tamamen açıldıktan sonra LAG'i aç. Otomatik döngü, VPN açıkken seçtiğin AÇIK/KAPALI sürelerini tekrarlar. Seçtiğin uygulamaların trafiği tünelden geçer; yüksek gecikme oyunun bağlantısını kesebilir."
            textSize = 12f
            setTextColor(Color.rgb(150, 161, 178))
            setPadding(0, dp(14), 0, 0)
        }

        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(prefs.getInt(KEY_BUTTON_SIZE, 64))))
        root.addView(disconnect, LinearLayout.LayoutParams(-1, -2))
        root.addView(testButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(testStatus, ViewGroup.LayoutParams(-1, -2))
        root.addView(settingsTitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(delayLabel, ViewGroup.LayoutParams(-1, -2))
        root.addView(delaySeek, ViewGroup.LayoutParams(-1, -2))
        root.addView(sizeLabel, ViewGroup.LayoutParams(-1, -2))
        root.addView(sizeSeek, ViewGroup.LayoutParams(-1, -2))
        root.addView(autoCycle, ViewGroup.LayoutParams(-1, -2))
        root.addView(onTimeLabel, ViewGroup.LayoutParams(-1, -2))
        root.addView(onSeek, ViewGroup.LayoutParams(-1, -2))
        root.addView(offTimeLabel, ViewGroup.LayoutParams(-1, -2))
        root.addView(offSeek, ViewGroup.LayoutParams(-1, -2))
        root.addView(info, ViewGroup.LayoutParams(-1, -2))

        val appTitle = TextView(this).apply {
            text = "OYUN / UYGULAMA SEÇİMİ"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(0, 235, 170))
            setPadding(0, dp(22), 0, dp(5))
        }
        val appDescription = TextView(this).apply {
            text = "İşaretlediğin uygulamaların trafiği gecikme tünelinden geçer. Seçimi değiştirdikten sonra aşağıdaki düğmeye bas."
            textSize = 12f
            setTextColor(Color.rgb(150, 161, 178))
            setPadding(0, 0, 0, dp(8))
        }
        appSelectionStatus = label("Seçili uygulama: " + selectedApps.size)
        root.addView(appTitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(appDescription, ViewGroup.LayoutParams(-1, -2))
        root.addView(appSelectionStatus, ViewGroup.LayoutParams(-1, -2))
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (launchableApps.isEmpty()) {
            root.addView(label("Gösterilecek uygulama bulunamadı."), ViewGroup.LayoutParams(-1, -2))
        } else {
            launchableApps.forEach { app ->
                val check = CheckBox(this).apply {
                    text = app.loadLabel(packageManager).toString()
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    isChecked = selectedApps.contains(app.packageName)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 235, 170))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedApps.add(app.packageName) else selectedApps.remove(app.packageName)
                        prefs.edit().putStringSet(KEY_SELECTED_APPS, selectedApps.toSet()).apply()
                        appSelectionStatus.text = "Seçili uygulama: " + selectedApps.size
                    }
                }
                root.addView(check, ViewGroup.LayoutParams(-1, -2))
            }
        }
        val applyApps = Button(this).apply {
            text = "SEÇİLEN UYGULAMALARI UYGULA"
            setOnClickListener { applySelectedApps() }
        }
        root.addView(applyApps, ViewGroup.LayoutParams(-1, -2))
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(220, 228, 239))
        setPadding(0, dp(8), 0, 0)
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun loadSelectedApps() {
        val saved = prefs.getStringSet(KEY_SELECTED_APPS, null)
        if (saved != null) {
            selectedApps.clear()
            selectedApps.addAll(saved)
        } else {
            val oxideInstalled = runCatching { packageManager.getApplicationInfo(OXIDE_PACKAGE, 0) }.isSuccess
            if (oxideInstalled) selectedApps.add(OXIDE_PACKAGE)
        }
    }

    private fun applySelectedApps() {
        prefs.edit().putStringSet(KEY_SELECTED_APPS, selectedApps.toSet()).apply()
        if (vpnActive) {
            statusMessage = "Uygulama listesi uygulanıyor; VPN yeniden bağlanıyor…"
            refresh()
            startService(Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_RESTART))
        } else {
            statusMessage = "Seçim kaydedildi. VPN'i başlatınca uygulanacak."
            refresh()
            android.widget.Toast.makeText(this, statusMessage, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun applyToggleSize(size: Int) {
        toggle.layoutParams = toggle.layoutParams?.apply { height = dp(size) }
        toggle.requestLayout()
        if (vpnActive) startService(Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_RESIZE_OVERLAY))
    }

    private fun scheduleCycle() {
        uiHandler.removeCallbacks(cycleAction)
        if (!::autoCycle.isInitialized || !autoCycle.isChecked || !vpnActive) return
        val seconds = if (lagEnabled) prefs.getInt(KEY_ON_SECONDS, 5) else prefs.getInt(KEY_OFF_SECONDS, 5)
        uiHandler.postDelayed(cycleAction, seconds.coerceIn(1, 30) * 1000L)
    }

    private fun onToggle() {
        if (vpnActive) {
            val next = !lagEnabled
            statusMessage = if (next) "Gecikme açılıyor…" else "Gecikme kapatılıyor…"
            refresh()
            startService(Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_SET_DELAY)
                .putExtra(LagVpnService.EXTRA_DELAY_ENABLED, next))
            scheduleCycle()
            return
        }
        statusMessage = "VPN tüneli başlatılıyor…"
        refresh()
        if (!Settings.canDrawOverlays(this)) {
            statusMessage = "Yüzen düğme için ekran üstü izni gerekli"
            refresh()
            val overlayIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(overlayIntent, requestOverlay)
            return
        }
        beginVpnAuthorization()
    }

    private fun beginVpnAuthorization() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, requestVpn) else startLagVpnService()
    }

    private fun startLagVpnService() {
        val intent = Intent(this, LagVpnService::class.java).setAction(LagVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun disconnectVpn() {
        if (!vpnActive) return
        uiHandler.removeCallbacks(cycleAction)
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
        }.apply { name = "AnonH4X-ConnectivityTest"; isDaemon = true }.start()
    }

    @Deprecated("Deprecated API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestOverlay) {
            if (Settings.canDrawOverlays(this)) beginVpnAuthorization()
            else { statusMessage = "Ekran üstü izni verilmedi; yüzen düğme görünemez"; refresh() }
        } else if (requestCode == requestVpn) {
            if (resultCode == RESULT_OK) startLagVpnService()
            else { statusMessage = "VPN izni verilmedi"; refresh() }
        }
    }

    private fun refresh() {
        status.text = "DURUM: $statusMessage"
        toggle.text = if (lagEnabled) "LAG SWITCH  •  KAPAT" else "LAG SWITCH  •  AÇ"
        toggle.setBackgroundColor(if (lagEnabled) Color.rgb(180, 58, 66) else Color.rgb(0, 160, 115))
        disconnect.isEnabled = vpnActive
        testButton.isEnabled = vpnActive && !testRunning
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        uiHandler.removeCallbacks(cycleAction)
        if (receiverRegistered) unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    companion object {
        private const val KEY_BUTTON_SIZE = "button_size_dp"
        private const val KEY_AUTO_CYCLE = "auto_cycle"
        private const val KEY_ON_SECONDS = "on_seconds"
        private const val KEY_OFF_SECONDS = "off_seconds"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val OXIDE_PACKAGE = "com.catsbit.oxidesurvivalisland"
    }
}
