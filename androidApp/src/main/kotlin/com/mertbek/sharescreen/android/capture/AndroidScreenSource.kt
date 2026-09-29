package com.mertbek.sharescreen.android.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.mertbek.sharescreen.capture.AndroidScreenCapture
import com.mertbek.sharescreen.platform.ScreenSource
import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.settings.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class CaptureGrant(val resultCode: Int, val data: Intent)

class CaptureRequests {
    @Volatile
    var launcher: ((shareAudio: Boolean, wholeScreenOnly: Boolean) -> Unit)? = null

    private var pending: CompletableDeferred<CaptureGrant?>? = null

    suspend fun request(shareAudio: Boolean, wholeScreenOnly: Boolean): CaptureGrant? {
        val launch = launcher ?: return null
        val result = CompletableDeferred<CaptureGrant?>()
        pending = result
        withContext(Dispatchers.Main) { launch(shareAudio, wholeScreenOnly) }
        return result.await()
    }

    fun deliver(grant: CaptureGrant?) {
        pending?.complete(grant)
        pending = null
    }
}

class AndroidScreenSource(
    private val context: Context,
    private val requests: CaptureRequests,
    private val capture: AndroidScreenCapture,
) : ScreenSource {

    @Volatile
    private var starting: CompletableDeferred<CapturedMedia?>? = null

    override suspend fun start(quality: VideoQuality, shareAudio: Boolean, wholeScreenOnly: Boolean): CapturedMedia? {
        val grant = requests.request(shareAudio, wholeScreenOnly) ?: return null
        val result = CompletableDeferred<CapturedMedia?>()
        starting = result
        try {
            context.startForegroundService(ScreenCaptureService.startIntent(context, grant, quality, shareAudio))
            return withTimeoutOrNull(START_TIMEOUT) { result.await() }
        } finally {
            starting = null
        }
    }

    override fun stop() = capture.stop()

    internal fun startCapture(projection: MediaProjection, quality: VideoQuality, shareAudio: Boolean) {
        val media = try {
            capture.start(projection, quality, shareAudio)
        } catch (e: Exception) {
            starting?.complete(null)
            throw e
        }
        starting?.complete(media)
    }

    internal fun startFailed() {
        starting?.complete(null)
    }

    private companion object {
        val START_TIMEOUT = 15.seconds
    }
}
