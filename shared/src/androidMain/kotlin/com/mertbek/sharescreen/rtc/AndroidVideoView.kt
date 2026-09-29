package com.mertbek.sharescreen.rtc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.mertbek.sharescreen.control.Zoom
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun AndroidVideoView(
    video: RemoteVideo,
    eglBaseContext: EglBase.Context,
    modifier: Modifier,
    zoom: Zoom,
    onVideoSize: (Int, Int) -> Unit,
) {
    val track = (video as AndroidRemoteVideo).track
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    var frameAspectRatio by remember { mutableStateOf<Float?>(null) }
    val currentOnVideoSize by rememberUpdatedState(onVideoSize)

    DisposableEffect(renderer, eglBaseContext) {
        renderer.init(eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                val rotated = rotation % 180 != 0
                val shownWidth = if (rotated) height else width
                val shownHeight = if (rotated) width else height
                renderer.post {
                    frameAspectRatio = shownWidth.toFloat() / shownHeight
                    currentOnVideoSize(shownWidth, shownHeight)
                }
            }
        })
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        onDispose { renderer.release() }
    }
    DisposableEffect(track, renderer) {
        runCatching { track.addSink(renderer) }
        onDispose { runCatching { track.removeSink(renderer) } }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            (frameAspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                .clipToBounds()
                .onSizeChanged { size = it },
        ) {
            AndroidView(
                factory = { renderer },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.pivotX = 0f
                    view.pivotY = 0f
                    view.scaleX = zoom.scale
                    view.scaleY = zoom.scale
                    view.translationX = -zoom.left * zoom.scale * size.width
                    view.translationY = -zoom.top * zoom.scale * size.height
                },
            )
        }
    }
}
