package com.mertbek.sharescreen.audio

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PcmFormat(val sampleRate: Int, val channels: Int)

class WasapiLoopback(private val onPcm: (ShortArray) -> Unit) : AutoCloseable {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(): PcmFormat? {
        if (running) return null
        running = true
        val ready = CompletableFuture<PcmFormat>()
        val worker = Thread({ capture(ready) }, "wasapi-loopback").apply { isDaemon = true }
        thread = worker
        worker.start()
        return try {
            ready.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            close()
            null
        }
    }

    override fun close() {
        running = false
        thread?.join(JOIN_TIMEOUT_MILLIS)
        thread = null
    }

    private fun capture(ready: CompletableFuture<PcmFormat>) {
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).toInt()
        var enumerator: Pointer? = null
        var device: Pointer? = null
        var client: Pointer? = null
        var captureClient: Pointer? = null
        var mixFormat: Pointer? = null
        var started = false
        try {
            enumerator = createInstance(CLSID_MMDEVICE_ENUMERATOR, IID_IMMDEVICE_ENUMERATOR)
            device = output { call(enumerator, ENUMERATOR_GET_DEFAULT_ENDPOINT, DATA_FLOW_RENDER, ROLE_CONSOLE, it) }
            client = output { call(device, DEVICE_ACTIVATE, IID_IAUDIO_CLIENT, WTypes.CLSCTX_ALL, null, it) }
            mixFormat = output { call(client, CLIENT_GET_MIX_FORMAT, it) }
            val layout = Layout.read(mixFormat)
            check(
                call(client, CLIENT_INITIALIZE, SHARE_MODE_SHARED, STREAM_FLAG_LOOPBACK, BUFFER_DURATION, 0L, mixFormat, null) >= 0,
            ) { "Could not initialize loopback capture" }
            captureClient = output { call(client, CLIENT_GET_SERVICE, IID_IAUDIO_CAPTURE_CLIENT, it) }
            check(call(client, CLIENT_START) >= 0) { "Could not start loopback capture" }
            started = true
            ready.complete(PcmFormat(layout.sampleRate, minOf(layout.channels, MAX_OUTPUT_CHANNELS)))
            while (running) {
                Thread.sleep(POLL_MILLIS)
                drain(captureClient, layout)
            }
        } catch (e: Throwable) {
            ready.completeExceptionally(e)
        } finally {
            if (started) call(client!!, CLIENT_STOP)
            mixFormat?.let { Ole32.INSTANCE.CoTaskMemFree(it) }
            listOfNotNull(captureClient, client, device, enumerator).forEach { call(it, RELEASE) }
            if (initialized >= 0) Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun drain(captureClient: Pointer, layout: Layout) {
        while (true) {
            val packet = IntByReference()
            if (call(captureClient, CAPTURE_GET_NEXT_PACKET_SIZE, packet) < 0 || packet.value == 0) return
            val data = PointerByReference()
            val frames = IntByReference()
            val flags = IntByReference()
            if (call(captureClient, CAPTURE_GET_BUFFER, data, frames, flags, null, null) < 0) return
            val count = frames.value
            val pcm = if (flags.value and BUFFER_FLAG_SILENT != 0) {
                ShortArray(count * minOf(layout.channels, MAX_OUTPUT_CHANNELS))
            } else {
                layout.toPcm(data.value, count)
            }
            call(captureClient, CAPTURE_RELEASE_BUFFER, count)
            onPcm(pcm)
        }
    }

    private class Layout(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
    ) {
        fun toPcm(data: Pointer, frames: Int): ShortArray {
            val outChannels = minOf(channels, MAX_OUTPUT_CHANNELS)
            val out = ShortArray(frames * outChannels)
            val bytesPerSample = bitsPerSample / 8
            for (frame in 0 until frames) {
                for (channel in 0 until outChannels) {
                    val offset = ((frame * channels + channel) * bytesPerSample).toLong()
                    out[frame * outChannels + channel] = sample(data, offset)
                }
            }
            return out
        }

        private fun sample(data: Pointer, offset: Long): Short = when {
            isFloat && bitsPerSample == 32 -> (data.getFloat(offset).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            bitsPerSample == 16 -> data.getShort(offset)
            bitsPerSample == 24 -> data.getShort(offset + 1)
            bitsPerSample == 32 -> (data.getInt(offset) shr 16).toShort()
            else -> 0
        }

        companion object {
            fun read(format: Pointer): Layout {
                val tag = format.getShort(0).toInt() and 0xFFFF
                val channels = format.getShort(2).toInt() and 0xFFFF
                val sampleRate = format.getInt(4)
                val bits = format.getShort(14).toInt() and 0xFFFF
                val isFloat = tag == TAG_IEEE_FLOAT || (tag == TAG_EXTENSIBLE && format.getInt(EXTENSIBLE_SUBFORMAT_OFFSET) == TAG_IEEE_FLOAT)
                return Layout(sampleRate, channels, bits, isFloat)
            }
        }
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val JOIN_TIMEOUT_MILLIS = 2000L
        const val POLL_MILLIS = 5L
        const val MAX_OUTPUT_CHANNELS = 2
        const val BUFFER_DURATION = 2_000_000L

        const val SHARE_MODE_SHARED = 0
        const val STREAM_FLAG_LOOPBACK = 0x00020000
        const val BUFFER_FLAG_SILENT = 0x2
        const val DATA_FLOW_RENDER = 0
        const val ROLE_CONSOLE = 0
        const val TAG_IEEE_FLOAT = 3
        const val TAG_EXTENSIBLE = 0xFFFE
        const val EXTENSIBLE_SUBFORMAT_OFFSET = 24L

        const val RELEASE = 2
        const val ENUMERATOR_GET_DEFAULT_ENDPOINT = 4
        const val DEVICE_ACTIVATE = 3
        const val CLIENT_INITIALIZE = 3
        const val CLIENT_GET_MIX_FORMAT = 8
        const val CLIENT_START = 10
        const val CLIENT_STOP = 11
        const val CLIENT_GET_SERVICE = 14
        const val CAPTURE_GET_BUFFER = 3
        const val CAPTURE_RELEASE_BUFFER = 4
        const val CAPTURE_GET_NEXT_PACKET_SIZE = 5

        val CLSID_MMDEVICE_ENUMERATOR = Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
        val IID_IMMDEVICE_ENUMERATOR = Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        val IID_IAUDIO_CLIENT = Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
        val IID_IAUDIO_CAPTURE_CLIENT = Guid.GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")

        fun createInstance(clsid: Guid.GUID, iid: Guid.GUID): Pointer {
            val result = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(clsid, null, WTypes.CLSCTX_ALL, iid, result).toInt()
            check(hr >= 0) { "CoCreateInstance failed: 0x${hr.toUInt().toString(16)}" }
            return result.value
        }

        inline fun output(block: (PointerByReference) -> Int): Pointer {
            val result = PointerByReference()
            val hr = block(result)
            check(hr >= 0) { "COM call failed: 0x${hr.toUInt().toString(16)}" }
            return result.value
        }

        fun call(self: Pointer, index: Int, vararg args: Any?): Int {
            val table = self.getPointer(0)
            val function = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return function.invokeInt(arrayOf<Any?>(self, *args))
        }
    }
}
