package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.audio.SystemAudioSource
import com.mertbek.sharescreen.settings.VideoQuality
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import java.awt.GraphicsEnvironment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DesktopScreenCapture(private val engine: DesktopRtcEngine) {
    private var media: DesktopCapturedMedia? = null

    val isActive: Boolean get() = media != null

    fun screens(): List<DesktopSource> {
        engine.factory
        val capturer = ScreenCapturer()
        return try {
            capturer.desktopSources
        } finally {
            capturer.dispose()
        }
    }

    fun start(quality: VideoQuality, screen: DesktopSource? = null, shareAudio: Boolean = false): DesktopCapturedMedia {
        check(media == null) { "Capture is already running" }
        val factory = engine.factory
        val target = screen ?: screens().firstOrNull() ?: throw RtcException("No screen to capture")
        val (width, height) = capturedSize(quality.maxLongEdge)
        val source = VideoDesktopSource().apply {
            setSourceId(target.id, false)
            setFrameRate(FRAME_RATE)
            setMaxFrameSize(width, height)
            start()
        }
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        val systemAudio = if (shareAudio) SystemAudioSource.create(factory) else null
        val captured = DesktopCapturedMedia(track, width, height, quality.maxBitrateBps, systemAudio?.track) {
            systemAudio?.close()
            source.stop()
            source.dispose()
        }
        media = captured
        return captured
    }

    fun stop() {
        media?.release()
        media = null
    }

    private fun capturedSize(maxLongEdge: Int): Pair<Int, Int> {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val transform = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform
        val width = (bounds.width * transform.scaleX).roundToInt()
        val height = (bounds.height * transform.scaleY).roundToInt()
        val scale = min(1f, maxLongEdge.toFloat() / max(width, height))
        return even((width * scale).roundToInt()) to even((height * scale).roundToInt())
    }

    private fun even(value: Int) = value and 1.inv()

    private companion object {
        const val VIDEO_TRACK_ID = "screen"
        const val FRAME_RATE = 30
    }
}
