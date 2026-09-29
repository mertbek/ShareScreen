package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoTrack

class DesktopCapturedMedia(
    val video: VideoTrack,
    override val width: Int,
    override val height: Int,
    override val maxVideoBitrateBps: Int,
    val audio: AudioTrack? = null,
    private val releaseSource: () -> Unit,
) : CapturedMedia {
    override val hasAudio: Boolean = audio != null
    override val preview: RemoteVideo = DesktopRemoteVideo(video)

    fun release() {
        video.dispose()
        releaseSource()
    }
}

class DesktopRemoteVideo(val track: VideoTrack) : RemoteVideo

class DesktopRemoteAudio(val track: AudioTrack) : RemoteAudio {
    override fun setEnabled(enabled: Boolean) {
        track.isEnabled = enabled
    }
}
