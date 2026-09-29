package com.mertbek.sharescreen.audio

import com.mertbek.sharescreen.util.Log
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

class SystemAudioSource private constructor(
    private val source: CustomAudioSource,
    val track: AudioTrack,
    private val loopback: WasapiLoopback,
    private val pacer: Pacer,
) : AutoCloseable {

    override fun close() {
        loopback.close()
        pacer.close()
        track.dispose()
        source.dispose()
    }

    internal class Pacer(private val source: CustomAudioSource, private val format: PcmFormat) : AutoCloseable {
        private val lock = Any()
        private var queued = ShortArray(0)
        private var size = 0

        @Volatile
        private var running = true
        private val thread = Thread({ run() }, "system-audio-pacer").apply { isDaemon = true }

        private val frameSamples = format.sampleRate / FRAMES_PER_SECOND * format.channels

        fun start() = thread.start()

        fun add(pcm: ShortArray) {
            synchronized(lock) {
                val limit = frameSamples * MAX_QUEUED_FRAMES
                val total = size + pcm.size
                if (total > queued.size) queued = queued.copyOf(maxOf(total, queued.size * 2))
                pcm.copyInto(queued, size)
                size = total
                if (size > limit) {
                    queued.copyInto(queued, 0, size - limit, size)
                    size = limit
                }
            }
        }

        override fun close() {
            running = false
            thread.join(JOIN_TIMEOUT_MILLIS)
        }

        private fun run() {
            var next = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                if (now < next) {
                    LockSupport.parkNanos(next - now)
                    continue
                }
                if (now - next > MAX_LAG_NANOS) next = now
                next += FRAME_NANOS
                push()
            }
        }

        private fun push() {
            val samples = ShortArray(frameSamples)
            synchronized(lock) {
                val taken = min(frameSamples, size)
                queued.copyInto(samples, 0, 0, taken)
                queued.copyInto(queued, 0, taken, size)
                size -= taken
            }
            val bytes = ByteArray(frameSamples * 2)
            for (index in samples.indices) {
                val value = samples[index].toInt()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value shr 8).toByte()
            }
            source.pushAudio(bytes, BITS_PER_SAMPLE, format.sampleRate, format.channels, frameSamples / format.channels)
        }
    }

    companion object {
        val isSupported: Boolean = System.getProperty("os.name").lowercase().contains("windows")

        fun create(factory: PeerConnectionFactory): SystemAudioSource? {
            if (!isSupported) return null
            var pacer: Pacer? = null
            val source = CustomAudioSource()
            val loopback = WasapiLoopback { pcm -> pacer?.add(pcm) }
            val format = try {
                loopback.start()
            } catch (e: Throwable) {
                Log.w(TAG, "System audio capture is not available", e)
                null
            }
            if (format == null) {
                source.dispose()
                return null
            }
            pacer = Pacer(source, format).also { it.start() }
            return SystemAudioSource(source, factory.createAudioTrack(TRACK_ID, source), loopback, pacer)
        }

        private const val TAG = "SystemAudioSource"
        private const val TRACK_ID = "screen-audio"
        private const val BITS_PER_SAMPLE = 16
        private const val FRAMES_PER_SECOND = 100
        private const val FRAME_NANOS = 1_000_000_000L / FRAMES_PER_SECOND
        private const val MAX_LAG_NANOS = 200_000_000L
        private const val MAX_QUEUED_FRAMES = 20
        private const val JOIN_TIMEOUT_MILLIS = 1000L
    }
}
