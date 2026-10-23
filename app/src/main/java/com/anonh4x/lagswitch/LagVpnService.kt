package com.anonh4x.lagswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Owns the TUN and routes only Oxide and the in-app connectivity test through it. */
class LagVpnService : VpnService() {
    @Volatile private var engine: NetstackEngine? = null
    private val detachedTunFd = AtomicInteger(-1)
    @Volatile private var vpnActive = false
    @Volatile internal var lagEnabled = false
        private set
    @Volatile private var starting = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop("VPN tüneli kapatıldı")
            ACTION_SET_DELAY -> setDelay(intent.getBooleanExtra(EXTRA_DELAY_ENABLED, false))
            ACTION_START, null -> {
                if (engine == null) {
                    lagEnabled = true
                    enterForeground()
                    beginStart()
                } else setDelay(true)
            }
            else -> beginStart()
        }
        return START_NOT_STICKY
    }

    private fun beginStart() {
        if (starting || engine != null) return
        starting = true
        publishState(false, false, "Tünel kuruluyor…")
        serviceScope.launch {
            try {
                val pfd = buildTun()
                val fd = pfd.detachFd()
                val newEngine = NetstackEngine(this@LagVpnService, { lagEnabled }, { (getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DELAY_MS, 1000)).toLong() })
                try { newEngine.start(fd) }
                catch (t: Throwable) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    throw t
                }
                detachedTunFd.set(fd)
                engine = newEngine
                vpnActive = true
                showFloatingButton()
                publishState(true, lagEnabled, if (lagEnabled) "VPN açık • gecikme AÇIK (gecikme ayarına göre)" else "VPN açık • gecikme KAPALI")
                refreshNotification()
                Log.i(TAG, "VPN started; delayEnabled=$lagEnabled")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "VPN start failed", t)
                failAndStop("HATA: ${t.message ?: t.javaClass.simpleName}")
            } finally { starting = false }
        }
    }

    private fun buildTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("AnonH4X Fake Lag")
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUN_IPV6, 128)
            .addRoute("::", 0)
            .addDnsServer(DNS_V4)
            .addDnsServer(DNS_V6)
        try {
            builder.addAllowedApplication(OXIDE_PACKAGE)
            builder.addAllowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Oxide: Survival Island kurulu görünmüyor. Oyunu yükleyip tekrar dene.", e)
        }
        builder.setBlocking(true)
        return builder.establish() ?: throw IOException("Android VPN tünelini kuramadı veya izin geri alındı.")
    }

    private fun setDelay(enabled: Boolean) {
        if (engine == null || !vpnActive) {
            lagEnabled = enabled
            if (!starting) { enterForeground(); beginStart() }
            return
        }
        lagEnabled = enabled
        val message = if (enabled) "VPN açık • gecikme AÇIK (1000 ms/yön)" else "VPN açık • gecikme KAPALI"
        publishState(true, enabled, message)
        updateFloatingButton()
        refreshNotification()
        Log.i(TAG, "delayEnabled=$enabled")
    }

    private fun showFloatingButton() {
        if (!android.provider.Settings.canDrawOverlays(this) || floatingButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val sizeDp = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("button_size_dp", 64).coerceIn(48, 104)
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val button = TextView(this).apply {
            text = if (lagEnabled) "ANON\nLAG ON" else "ANON\nLAG OFF"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (lagEnabled) Color.rgb(180, 58, 66) else Color.rgb(0, 150, 110))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.rgb(0, 235, 170))
            }
            elevation = 8 * resources.displayMetrics.density
            contentDescription = "AnonH4X Fake Lag aç veya kapat"
        }
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * resources.displayMetrics.density).toInt()
            y = (220 * resources.displayMetrics.density).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) dragged = true
                    if (dragged) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged && vpnActive) setDelay(!lagEnabled)
                    true
                }
                else -> true
            }
        }
        try {
            windowManager?.addView(button, params)
            floatingButton = button
            floatingParams = params
        } catch (t: Throwable) {
            Log.w(TAG, "Could not show floating button", t)
        }
    }

    private fun updateFloatingButton() {
        val button = floatingButton ?: return
        button.text = if (lagEnabled) "ANON\nLAG ON" else "ANON\nLAG OFF"
        (button.background as? GradientDrawable)?.setColor(
            if (lagEnabled) Color.rgb(180, 58, 66) else Color.rgb(0, 150, 110)
        )
    }

    private fun hideFloatingButton() {
        val button = floatingButton
        if (button != null) runCatching { windowManager?.removeView(button) }
        floatingButton = null
        floatingParams = null
        windowManager = null
    }

    private fun requestStop(message: String) { serviceScope.launch { stopTunnel(message) } }
    private suspend fun failAndStop(message: String) = stopTunnel(message)
    private fun stopTunnelAsync(message: String) { serviceScope.launch { stopTunnel(message) } }

    private suspend fun stopTunnel(message: String) {
        hideFloatingButton()
        val oldEngine = engine
        engine = null
        vpnActive = false
        lagEnabled = false
        runCatching { oldEngine?.stop() }
        val oldFd = detachedTunFd.getAndSet(-1)
        if (oldFd >= 0) runCatching { ParcelFileDescriptor.adoptFd(oldFd).close() }
        publishState(false, false, message)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AnonH4X Fake Lag VPN", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "AnonH4X Fake Lag bağlantı durumu" },
            )
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ANONH4X LagSwitch")
            .setContentText(if (lagEnabled) "Oxide trafiği gecikmeli" else "VPN açık, gecikme kapalı")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun refreshNotification() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ANONH4X LagSwitch")
            .setContentText(if (lagEnabled) "Oxide trafiği 1000 ms gecikmeli" else "VPN açık, gecikme kapalı")
            .setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun publishState(active: Boolean, delay: Boolean, message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_VPN_ACTIVE, active).putBoolean(KEY_DELAY_ENABLED, delay).putString(KEY_STATUS, message).apply()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_VPN_ACTIVE, active).putExtra(EXTRA_DELAY_ENABLED, delay).putExtra(EXTRA_STATUS, message))
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopTunnelAsync("VPN izni geri alındı")
    }
    override fun onDestroy() {
        hideFloatingButton()
        val old = engine
        engine = null
        runCatching { old?.stop() }
        val oldFd = detachedTunFd.getAndSet(-1)
        if (oldFd >= 0) runCatching { ParcelFileDescriptor.adoptFd(oldFd).close() }
        vpnActive = false
        lagEnabled = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.anonh4x.lagswitch.START"
        const val ACTION_STOP = "com.anonh4x.lagswitch.STOP"
        const val ACTION_SET_DELAY = "com.anonh4x.lagswitch.SET_DELAY"
        const val ACTION_STATE = "com.anonh4x.lagswitch.STATE"
        const val EXTRA_DELAY_ENABLED = "delay_enabled"
        const val EXTRA_VPN_ACTIVE = "vpn_active"
        const val EXTRA_STATUS = "status_text"
        const val PREFS = "lag_switch_state"
        const val KEY_VPN_ACTIVE = "vpn_active"
        const val KEY_DELAY_ENABLED = "delay_enabled"
        const val KEY_STATUS = "status_text"
        const val KEY_DELAY_MS = "delay_ms"
        private const val TAG = "LagSwitch"
        private const val CHANNEL_ID = "lagswitch_vpn"
        private const val NOTIFICATION_ID = 7301
        private const val MTU = 1500
        private const val TUN_IPV4 = "10.111.0.2"
        private const val TUN_IPV6 = "fd00:6e76:6376::2"
        private const val DNS_V4 = "1.1.1.1"
        private const val DNS_V6 = "2606:4700:4700::1111"
        private const val OXIDE_PACKAGE = "com.catsbit.oxidesurvivalisland"
    }
}
