package com.anonh4x.lagswitch

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var active = false
    private val requestVpn = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 20)
        }
        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener { onToggle() }
        }
        val info = TextView(this).apply {
            text = "Hedef gecikme: 1000 ms\n\nBu ilk prototip VPN izin akışını ve düğmeyi içerir. Paket geciktirme motoru henüz ekli değildir; ON durumu gerçek oyunda lag oluşturduğu anlamına gelmez."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 26, 0, 0)
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        root.addView(info, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun onToggle() {
        if (active) {
            stopService(Intent(this, LagVpnService::class.java))
            active = false
            refresh()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, requestVpn) else startVpnService()
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVpn && resultCode == RESULT_OK) startVpnService()
    }

    private fun startVpnService() {
        startService(Intent(this, LagVpnService::class.java))
        active = true
        refresh()
    }

    private fun refresh() {
        status.text = if (active) "DURUM: AÇIK (VPN prototipi)" else "DURUM: KAPALI"
        toggle.text = if (active) "LAG SWITCH  •  OFF" else "LAG SWITCH  •  ON"
    }
}
