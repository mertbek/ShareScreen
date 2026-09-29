package com.mertbek.sharescreen.rtc

import android.content.Context
import android.media.AudioAttributes
import com.mertbek.sharescreen.capture.ScreenAudioInput
import com.mertbek.sharescreen.signaling.IceServerConfig
import com.mertbek.sharescreen.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.audio.JavaAudioDeviceModule

class AndroidRtcEngine(
    private val context: Context,
    val screenAudioInput: ScreenAudioInput,
) : RtcEngine {
    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioRecordStateCallback(screenAudioInput)
            .createAudioDeviceModule()
        screenAudioInput.attach(audioDeviceModule)

        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    val unprocessedAudioConstraints: MediaConstraints
        get() = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                mandatory.add(MediaConstraints.KeyValuePair(key, "false"))
            }
        }

    fun preferHardwareVideoCodec(transceiver: RtpTransceiver) {
        val video = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        val receivable = factory.getRtpReceiverCapabilities(video).codecs
        val codecs = factory.getRtpSenderCapabilities(video).codecs
            .filter { sent -> receivable.any { it.mimeType == sent.mimeType && it.parameters == sent.parameters } }
            .sortedBy { if (it.name.equals(H264, ignoreCase = true)) 0 else 1 }
        if (codecs.none { it.name.equals(H264, ignoreCase = true) }) return
        try {
            transceiver.setCodecPreferences(codecs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not prefer H.264", e)
        }
    }

    override fun createPeer(iceServers: List<IceServerConfig>): RtcPeer {
        val servers = iceServers.map { config ->
            PeerConnection.IceServer.builder(config.urls)
                .setUsername(config.username.orEmpty())
                .setPassword(config.credential.orEmpty())
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return AndroidPeer(this, config)
    }

    private companion object {
        const val TAG = "AndroidRtcEngine"
        const val H264 = "H264"
    }
}
