package com.anonh4x.lagswitch

import android.net.VpnService
import android.content.Intent
import android.util.Log

/**
 * VPN lifecycle scaffold only. No TUN interface or packet-forwarding engine is
 * created yet; doing so without a working forwarder would break connectivity.
 */
class LagVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("LagSwitch", "VPN scaffold started; packet engine is not installed.")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i("LagSwitch", "VPN scaffold stopped.")
        super.onDestroy()
    }
}
