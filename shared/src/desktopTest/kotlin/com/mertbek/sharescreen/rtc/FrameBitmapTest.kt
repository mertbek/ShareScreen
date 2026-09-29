package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrameBitmapTest {

    @Test
    fun `a red i420 frame becomes red pixels`() {
        DesktopRtcEngine().factory
        val buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT)
        fill(buffer.dataY, buffer.strideY, HEIGHT, 76)
        fill(buffer.dataU, buffer.strideU, HEIGHT / 2, 84)
        fill(buffer.dataV, buffer.strideV, HEIGHT / 2, 255)
        val frame = VideoFrame(buffer, 0)

        val frameBitmap = FrameBitmap()
        frameBitmap.onVideoFrame(frame)
        frame.release()

        val published = assertNotNull(frameBitmap.frame.value)
        assertEquals(WIDTH, published.width)
        assertEquals(HEIGHT, published.height)
        val pixels = IntArray(WIDTH * HEIGHT)
        assertNotNull(frameBitmap.draw { it.readPixels(pixels) })
        frameBitmap.close()
        val pixel = pixels[HEIGHT / 2 * WIDTH + WIDTH / 2]
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        assertTrue(red > 200 && green < 60 && blue < 60, "pixel was ${pixel.toUInt().toString(16)}")
    }

    private fun fill(plane: java.nio.ByteBuffer, stride: Int, rows: Int, value: Int) {
        val row = ByteArray(stride) { value.toByte() }
        for (y in 0 until rows) {
            plane.position(y * stride)
            plane.put(row)
        }
        plane.rewind()
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
    }
}
