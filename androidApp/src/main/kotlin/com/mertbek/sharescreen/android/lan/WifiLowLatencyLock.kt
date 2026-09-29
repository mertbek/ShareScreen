package com.mertbek.sharescreen.android.lan

import android.content.Context
import android.net.wifi.WifiManager

class WifiLowLatencyLock(context: Context) {

    private val lock = context.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ShareScreen:stream")
        .apply { setReferenceCounted(false) }

    fun acquire() = lock.acquire()

    fun release() {
        if (lock.isHeld) lock.release()
    }
}
