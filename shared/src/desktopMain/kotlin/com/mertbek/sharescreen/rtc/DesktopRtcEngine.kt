package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.signaling.IceServerConfig
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCRtcpMuxPolicy

class DesktopRtcEngine : RtcEngine {
    val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

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
