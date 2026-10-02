package com.mertbek.sharescreen.android.capture

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.ui.host.HostRequestCard
import com.mertbek.sharescreen.ui.theme.ShareScreenTheme
import com.mertbek.sharescreen.util.Log

/**
 * Shows the waiting watch or control request over other apps, where the app's own dialog cannot be seen.
 * It needs the "display over other apps" permission and does nothing without it.
 */
internal class RequestOverlay(private val context: Context, private val services: AppServices) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    val isAllowed: Boolean get() = Settings.canDrawOverlays(context)

    fun show() {
        if (view != null || !isAllowed) return
        val owner = OverlayOwner()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                ShareScreenTheme {
                    HostRequestCard(services, Modifier.padding(24.dp))
                }
            }
        }
        // Not focusable, so typing in the app underneath goes on and touches beside the card reach it.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            windowAnimations = android.R.style.Animation_Dialog
        }
        try {
            windowManager.addView(view, params)
            this.view = view
            this.owner = owner
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not show the request over other apps", e)
            owner.destroy()
        }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner?.destroy()
        owner = null
    }

    private companion object {
        const val TAG = "RequestOverlay"
    }
}

/** What Compose needs from a screen, for a view that lives in a window of its own. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
