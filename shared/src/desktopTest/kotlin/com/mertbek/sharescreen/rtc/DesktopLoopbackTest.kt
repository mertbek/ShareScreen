package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.settings.VideoQuality
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopLoopbackTest {

    @Test
    fun `video and control messages flow between two peers`() = runBlocking {
        val engine = DesktopRtcEngine()
        val factory = engine.factory
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("screen", source)
        val loopback = Loopback(engine, DesktopCapturedMedia(track, WIDTH, HEIGHT, 1_000_000) { source.dispose() })

        loopback.launch {
            while (true) {
                val frame = VideoFrame(NativeI420Buffer.allocate(WIDTH, HEIGHT), System.nanoTime())
                source.pushFrame(frame)
                frame.release()
                delay(33)
            }
        }

        try {
            withTimeout(30.seconds) {
                loopback.connect()
                assertTrue(loopback.hostChannel.send("hello"))
                assertEquals("hello", loopback.viewerChannel.messages.first())
                assertTrue(loopback.viewerChannel.send("reply"))
                assertEquals("reply", loopback.hostChannel.messages.first())
                loopback.awaitFrames(5)
            }
        } finally {
            loopback.close()
        }
    }

    @Test
    fun `the screen is captured and streamed`() = runBlocking {
        val engine = DesktopRtcEngine()
        val capture = DesktopScreenCapture(engine)
        val loopback = Loopback(engine, capture.start(VideoQuality.LOW))

        try {
            withTimeout(30.seconds) {
                loopback.connect()
                loopback.awaitFrames(5)
            }
        } finally {
            loopback.close()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }
}
