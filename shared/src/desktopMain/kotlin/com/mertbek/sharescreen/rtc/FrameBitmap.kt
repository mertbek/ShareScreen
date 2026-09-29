package com.mertbek.sharescreen.rtc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

class FrameBitmap : VideoTrackSink {
    private val busy = AtomicBoolean(false)
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    private var pixels = ByteArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var next = 0

    val image: StateFlow<ImageBitmap?> = _image

    override fun onVideoFrame(frame: VideoFrame) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val width = frame.buffer.width
            val height = frame.buffer.height
            val size = width * height * BYTES_PER_PIXEL
            if (pixels.size != size) pixels = ByteArray(size)
            VideoBufferConverter.convertFromI420(frame.buffer, pixels, FourCC.ARGB)
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val bitmap = bitmaps[next]?.takeIf { it.width == width && it.height == height }
                ?: Bitmap().also { it.allocPixels(info) }
            bitmaps[next] = bitmap
            bitmap.installPixels(info, pixels, width * BYTES_PER_PIXEL)
            next = 1 - next
            _image.value = bitmap.asComposeImageBitmap()
        } catch (_: Exception) {
        } finally {
            busy.set(false)
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
