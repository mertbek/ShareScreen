package com.mertbek.sharescreen.rtc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.HtmlElementView
import com.mertbek.sharescreen.control.Zoom
import org.w3c.dom.HTMLElement

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WebVideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom, onVideoSize: (Int, Int) -> Unit) {
    val stream = (video as WebRemoteVideo).stream
    val element = remember(stream) { createVideoElement(stream) }
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnVideoSize by rememberUpdatedState(onVideoSize)

    DisposableEffect(element) {
        videoOnSize(element) { width, height ->
            aspectRatio = width.toFloat() / height
            currentOnVideoSize(width, height)
        }
        onDispose { videoRelease(element) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            (aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                .clipToBounds()
                .onSizeChanged { size = it },
        ) {
            HtmlElementView(
                factory = { element.unsafeCast<HTMLElement>() },
                modifier = Modifier.fillMaxSize(),
                update = {
                    videoPassPointerEvents(element)
                    videoSetTransform(
                        element,
                        zoom.scale,
                        -zoom.left * zoom.scale * size.width,
                        -zoom.top * zoom.scale * size.height,
                    )
                },
            )
        }
    }
}
