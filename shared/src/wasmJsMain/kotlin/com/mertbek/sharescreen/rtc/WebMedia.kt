package com.mertbek.sharescreen.rtc

import kotlin.js.JsAny

class WebRemoteVideo(val stream: JsAny) : RemoteVideo

class WebRemoteAudio(track: JsAny) : RemoteAudio {
    private val element = audioAttach(track)

    override fun setEnabled(enabled: Boolean) = audioSetEnabled(element, enabled)
}

class WebCapturedMedia(
    val stream: JsAny,
    override val width: Int,
    override val height: Int,
    override val hasAudio: Boolean,
    override val maxVideoBitrateBps: Int,
) : CapturedMedia {
    override val preview: RemoteVideo = WebRemoteVideo(streamVideoOnly(stream))

    fun release() = streamStop(stream)
}
