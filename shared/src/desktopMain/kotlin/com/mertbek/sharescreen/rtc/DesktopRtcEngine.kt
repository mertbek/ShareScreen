package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.signaling.IceServerConfig
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCRtcpMuxPolicy
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioLayer

class DesktopRtcEngine(private val audioDevice: AudioDeviceModuleBase? = null) : RtcEngine {
    val factory: PeerConnectionFactory by lazy {
        audioDevice?.let { PeerConnectionFactory(it) } ?: createFactory()
    }

    private fun createFactory(): PeerConnectionFactory = try {
        PeerConnectionFactory()
    } catch (e: Error) {
        if (e.javaClass != Error::class.java) throw e
        PeerConnectionFactory(AudioDeviceModule(AudioLayer.kDummyAudio))
    }

    override fun createPeer(iceServers: List<IceServerConfig>): RtcPeer {
        val config = RTCConfiguration().apply {
            this.iceServers = iceServers.map { server ->
                RTCIceServer().apply {
                    urls = server.urls
                    username = server.username.orEmpty()
                    password = server.credential.orEmpty()
                }
            }
            bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        }
        return DesktopPeer(factory, config)
    }
}
