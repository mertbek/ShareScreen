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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.control.fitVideo
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect

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
        sink.draw { image ->
            val fit = fitVideo(size.width, size.height, image.width, image.height)
            val source = Rect.makeXYWH(zoom.left * image.width, zoom.top * image.height, zoom.size * image.width, zoom.size * image.height)
            val target = Rect.makeXYWH(fit.left, fit.top, fit.width, fit.height)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(image, source, target, SAMPLING, null, true)
            }
        }
    }
}

private val SAMPLING = FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST)
