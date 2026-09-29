package com.mertbek.sharescreen.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.math.max

class ScreenAudioInput(
    private val context: Context,
) : JavaAudioDeviceModule.AudioRecordStateCallback {

    private lateinit var module: JavaAudioDeviceModule

    @Volatile
    var projection: MediaProjection? = null

    fun attach(module: JavaAudioDeviceModule) {
        this.module = module
        module.setMicrophoneMute(true)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onWebRtcAudioRecordStart() {
        module.setMicrophoneMute(true)
        val projection = projection ?: return
        if (!hasPermission()) return
        try {
            val input = JavaAudioDeviceModule::class.java.getField("audioInput").get(module)
                ?: error("No audio input")
            val recordField = input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val microphone = recordField.get(input) as AudioRecord

            val screen = createPlaybackRecord(projection, microphone.sampleRate, microphone.channelCount, microphone.audioFormat)
            screen.startRecording()
            recordField.set(input, screen)
            microphone.stop()
            microphone.release()
            module.setMicrophoneMute(false)
            Log.d(TAG, "Sharing device audio (${microphone.sampleRate} Hz, ${microphone.channelCount} ch)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not switch to device audio; sending silence", e)
        }
    }

    override fun onWebRtcAudioRecordStop() {
        module.setMicrophoneMute(true)
    }

    @Suppress("MissingPermission")
    private fun createPlaybackRecord(
        projection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
    ): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val bytesPer100Ms = sampleRate / 10 * channelCount * BYTES_PER_SAMPLE
        val bufferSize = max(AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding) * 2, bytesPer100Ms)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Playback capture AudioRecord not initialized" }
        return record
    }

    private companion object {
        const val TAG = "ScreenAudioInput"
        const val BYTES_PER_SAMPLE = 2
    }
}
