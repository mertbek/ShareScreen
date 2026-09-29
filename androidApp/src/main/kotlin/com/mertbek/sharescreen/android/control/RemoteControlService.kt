package com.mertbek.sharescreen.android.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.mertbek.sharescreen.android.ShareScreenApp

class RemoteControlService : AccessibilityService() {

    private val injector get() = (application as ShareScreenApp).inputInjector

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCacheEnabled(false)
        injector?.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        injector?.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        injector?.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
