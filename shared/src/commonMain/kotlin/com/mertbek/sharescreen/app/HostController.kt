package com.mertbek.sharescreen.app

import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HostController(private val services: AppServices) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _media = MutableStateFlow<CapturedMedia?>(null)
    val media: StateFlow<CapturedMedia?> = _media.asStateFlow()

    fun start() {
        val source = services.screenSource ?: return
        if (_media.value != null) return
        scope.launch {
            val settings = services.settings.settings.value
            val allowControl = settings.allowRemoteControl && services.canBeControlled
            val captured = try {
                source.start(settings.quality, settings.shareAudio, wholeScreenOnly = allowControl)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the screen capture", e)
                null
            } ?: return@launch
            _media.value = captured
            services.host.start(captured, settings.internetServer, allowControl)
        }
    }

    fun stop() {
        scope.launch {
            services.host.stop().join()
            services.screenSource?.stop()
            _media.value = null
        }
    }

    private companion object {
        const val TAG = "HostController"
    }
}
