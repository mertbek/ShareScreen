package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.platform.ScreenSource
import com.mertbek.sharescreen.settings.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny

class WebScreenSource : ScreenSource {
    private var media: WebCapturedMedia? = null

    override var onEnded: (() -> Unit)? = null

    override suspend fun start(quality: VideoQuality, shareAudio: Boolean, wholeScreenOnly: Boolean): CapturedMedia? {
        stop()
        val stream = try {
            getDisplayMedia(quality.maxLongEdge, quality.maxLongEdge, shareAudio).await<JsAny?>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return null
        val size = streamVideoSize(stream).split('|').map { it.toIntOrNull() ?: 0 }
        val captured = WebCapturedMedia(stream, size[0], size[1], streamHasAudio(stream), quality.maxBitrateBps)
        streamOnEnded(stream) { onEnded?.invoke() }
        media = captured
        return captured
    }

    override fun stop() {
        media?.release()
        media = null
    }
}
