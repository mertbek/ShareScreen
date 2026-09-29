package com.mertbek.sharescreen.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.mertbek.sharescreen.rtc.AndroidCapturedMedia
import com.mertbek.sharescreen.rtc.AndroidRtcEngine
import com.mertbek.sharescreen.settings.VideoQuality
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class AndroidScreenCapture(
    private val context: Context,
    private val engine: AndroidRtcEngine,
) {
    private var projection: MediaProjection? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturer: ScreenVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var maxLongEdge = CaptureSize.DEFAULT_MAX_LONG_EDGE
    private var size: CaptureSize? = null

    private val displayManager get() = context.getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val display = CaptureSize.forDefaultDisplay(context, Int.MAX_VALUE)
            onContentResized(display.width, display.height)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    val isActive: Boolean get() = projection != null

    fun start(projection: MediaProjection, quality: VideoQuality, shareAudio: Boolean): AndroidCapturedMedia {
        check(this.projection == null) { "Capture is already running" }
        this.projection = projection
        maxLongEdge = quality.maxLongEdge

        val captureSize = CaptureSize.forDefaultDisplay(context, maxLongEdge)
        val factory = engine.factory
        val helper = SurfaceTextureHelper.create(CAPTURE_THREAD_NAME, engine.eglBase.eglBaseContext)
        val source = factory.createVideoSource(true)
        val capturer = ScreenVideoCapturer(projection, captureSize.densityDpi)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(captureSize.width, captureSize.height, FRAME_RATE)
        val videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source)

        val audioTrack = if (shareAudio && engine.screenAudioInput.hasPermission()) {
            engine.screenAudioInput.projection = projection
            val audioSource = factory.createAudioSource(engine.unprocessedAudioConstraints)
            this.audioSource = audioSource
            factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        } else {
            null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }

        textureHelper = helper
        videoSource = source
        this.capturer = capturer
        this.videoTrack = videoTrack
        this.audioTrack = audioTrack
        size = captureSize
        return AndroidCapturedMedia(videoTrack, audioTrack, captureSize.width, captureSize.height, quality.maxBitrateBps)
    }

    fun onContentResized(width: Int, height: Int) {
        val current = size ?: return
        if (width <= 0 || height <= 0) return
        val scaled = CaptureSize.scaled(width, height, current.densityDpi, maxLongEdge)
        if (scaled == current) return
        capturer?.changeCaptureFormat(scaled.width, scaled.height, FRAME_RATE)
        size = scaled
    }

    fun stop() {
        val projection = projection ?: return
        this.projection = null
        size = null
        engine.screenAudioInput.projection = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            displayManager.unregisterDisplayListener(displayListener)
        }

        capturer?.stopCapture()
        capturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        capturer = null
        videoTrack = null
        videoSource = null
        textureHelper = null
        audioTrack = null
        audioSource = null

        projection.stop()
    }

    private companion object {
        const val CAPTURE_THREAD_NAME = "ScreenCaptureThread"
        const val VIDEO_TRACK_ID = "screen"
        const val AUDIO_TRACK_ID = "screen-audio"
        const val FRAME_RATE = 30
    }
}
