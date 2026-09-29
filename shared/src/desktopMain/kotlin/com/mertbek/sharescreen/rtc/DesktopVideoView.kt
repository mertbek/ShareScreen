package com.mertbek.sharescreen.rtc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.control.fitVideo
import kotlin.math.roundToInt

@Composable
fun DesktopVideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom, onVideoSize: (Int, Int) -> Unit) {
    val track = (video as DesktopRemoteVideo).track
    val sink = remember(track) { FrameBitmap() }
    DisposableEffect(track) {
        track.addSink(sink)
        onDispose {
            track.removeSink(sink)
            sink.close()
        }
    }
    val frame by sink.frame.collectAsState()
    val width = frame?.width
    val height = frame?.height
    LaunchedEffect(width, height) {
        if (width != null && height != null) onVideoSize(width, height)
    }

    Canvas(modifier.background(Color.Black)) {
        frame ?: return@Canvas
        sink.draw { bitmap ->
            val fit = fitVideo(size.width, size.height, bitmap.width, bitmap.height)
            drawImage(
                image = bitmap,
                srcOffset = IntOffset((zoom.left * bitmap.width).roundToInt(), (zoom.top * bitmap.height).roundToInt()),
                srcSize = IntSize((zoom.size * bitmap.width).roundToInt(), (zoom.size * bitmap.height).roundToInt()),
                dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
