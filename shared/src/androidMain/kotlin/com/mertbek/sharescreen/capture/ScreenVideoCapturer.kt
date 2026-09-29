package com.mertbek.sharescreen.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class ScreenVideoCapturer(
    private val mediaProjection: MediaProjection,
    private val densityDpi: Int,
) : VideoCapturer, VideoSink {

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    @Synchronized
    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        surfaceTextureHelper = helper
        capturerObserver = observer
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val helper = checkNotNull(surfaceTextureHelper) { "initialize() must be called first" }
        check(virtualDisplay == null) { "Capture can only be started once per projection" }

        helper.setTextureSize(width, height)
        val surface = Surface(helper.surfaceTexture).also { surface = it }
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ShareScreen",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        capturerObserver?.onCapturerStarted(true)
        helper.startListening(this)
    }

    @Synchronized
    override fun stopCapture() {
        val helper = surfaceTextureHelper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            helper.stopListening()
            capturerObserver?.onCapturerStopped()
            virtualDisplay?.release()
            virtualDisplay = null
            surface?.release()
            surface = null
        }
    }

    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: return
        val display = virtualDisplay ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            helper.setTextureSize(width, height)
            display.resize(width, height, densityDpi)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }

    override fun dispose() = Unit

    override fun isScreencast(): Boolean = true
}
