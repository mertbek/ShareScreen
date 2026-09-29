package com.mertbek.sharescreen.audio

import com.mertbek.sharescreen.rtc.DesktopCapturedMedia
import com.mertbek.sharescreen.rtc.DesktopRtcEngine
import com.mertbek.sharescreen.rtc.Loopback
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SystemAudioTest {

    @Test
    fun `the loopback hears what the system plays`() {
        if (!SystemAudioSource.isSupported) return
        val peak = AtomicInteger()
        val loopback = WasapiLoopback { pcm -> peak.accumulateAndGet(pcm.maxOf { abs(it.toInt()) }, ::maxOf) }
        val format = loopback.start() ?: return
        try {
            assertTrue(format.sampleRate > 0 && format.channels in 1..2)
            playTone()
            assertTrue(peak.get() > MIN_PEAK, "peak was ${peak.get()}")
        } finally {
            loopback.close()
        }
    }

    @Test
    fun `system audio reaches the other peer`() = runBlocking {
        if (!SystemAudioSource.isSupported) return@runBlocking
        val engine = DesktopRtcEngine(HeadlessAudioDeviceModule())
        val factory = engine.factory
        val systemAudio = SystemAudioSource.create(factory) ?: return@runBlocking
        val videoSource = CustomVideoSource()
        val video = factory.createVideoTrack("screen", videoSource)
        val media = DesktopCapturedMedia(video, WIDTH, HEIGHT, 1_000_000, systemAudio.track) {
            systemAudio.close()
            videoSource.dispose()
        }
        val loopback = Loopback(engine, media)
        loopback.launch {
            while (true) {
                val frame = VideoFrame(NativeI420Buffer.allocate(WIDTH, HEIGHT), System.nanoTime())
                videoSource.pushFrame(frame)
                frame.release()
                delay(33)
            }
        }
        try {
            withTimeout(30.seconds) {
                loopback.connect()
                val tone = thread { repeat(TONE_REPEATS) { playTone() } }
                loopback.awaitAudioAbove(MIN_PEAK)
                tone.join()
            }
        } finally {
            loopback.close()
        }
    }

    private fun playTone() {
        val format = AudioFormat(TONE_RATE, 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        val samples = (TONE_RATE * TONE_SECONDS).toInt()
        val bytes = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = (sin(2 * PI * TONE_HZ * index / TONE_RATE) * TONE_AMPLITUDE * Short.MAX_VALUE).toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        line.write(bytes, 0, bytes.size)
        line.drain()
        line.close()
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val MIN_PEAK = 200
        const val TONE_RATE = 48_000f
        const val TONE_HZ = 440.0
        const val TONE_SECONDS = 0.4
        const val TONE_AMPLITUDE = 0.03
        const val TONE_REPEATS = 8
    }
}
