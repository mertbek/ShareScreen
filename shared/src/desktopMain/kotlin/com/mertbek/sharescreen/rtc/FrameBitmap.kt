package com.mertbek.sharescreen.rtc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class Frame(val width: Int, val height: Int, val number: Long)

class FrameBitmap : VideoTrackSink {
    private val busy = AtomicBoolean(false)
    private val lock = ReentrantReadWriteLock()
    private val retired = ArrayDeque<Image>()
    private var current: Image? = null
    private var currentBitmap: ImageBitmap? = null
    private var pixels = ByteArray(0)
    private var number = 0L
    private val _frame = MutableStateFlow<Frame?>(null)

    val frame: StateFlow<Frame?> = _frame

    override fun onVideoFrame(frame: VideoFrame) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val width = frame.buffer.width
            val height = frame.buffer.height
            val byteCount = width * height * BYTES_PER_PIXEL
            if (pixels.size != byteCount) pixels = ByteArray(byteCount)
            VideoBufferConverter.convertFromI420(frame.buffer, pixels, FourCC.ARGB)
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val image = Image.makeRaster(info, pixels, width * BYTES_PER_PIXEL)
            val bitmap = image.toComposeImageBitmap()
            lock.write {
                current?.let(retired::addLast)
                current = image
                currentBitmap = bitmap
                while (retired.size > KEEP_RETIRED) retired.removeFirst().close()
            }
            _frame.value = Frame(width, height, ++number)
        } catch (_: Exception) {
        } finally {
            busy.set(false)
        }
    }

    fun <T> draw(block: (ImageBitmap) -> T): T? = lock.read { currentBitmap?.let(block) }

    fun close() {
        lock.write {
            current?.close()
            current = null
            currentBitmap = null
            retired.forEach(Image::close)
            retired.clear()
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val KEEP_RETIRED = 3
    }
}
