package com.mertbek.sharescreen.rtc

import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

class AndroidCapturedMedia(
    val video: VideoTrack,
    val audio: AudioTrack?,
    override val width: Int,
    override val height: Int,
    override val maxVideoBitrateBps: Int,
) : CapturedMedia {
    override val hasAudio: Boolean get() = audio != null
    override val preview: RemoteVideo = AndroidRemoteVideo(video)
}

class AndroidRemoteVideo(val track: VideoTrack) : RemoteVideo

class AndroidRemoteAudio(private val track: AudioTrack) : RemoteAudio {
    override fun setEnabled(enabled: Boolean) {
        track.setEnabled(enabled)
    }
}
