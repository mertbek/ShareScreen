package com.mertbek.sharescreen.rtc

import kotlin.test.assertEquals
import kotlin.test.Test

class OpusSdpTest {

    private fun sdp(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n")

    @Test
    fun `adds stereo and bitrate to the opus fmtp line and keeps existing parameters`() {
        val input = sdp(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=rtpmap:63 red/48000/2",
            "a=fmtp:63 111/111",
        )
        val expected = sdp(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=128000",
            "a=rtpmap:63 red/48000/2",
            "a=fmtp:63 111/111",
        )
        assertEquals(expected, OpusSdp.preferStereoMusic(input))
    }

    @Test
    fun `overrides existing values instead of duplicating them`() {
        val input = sdp("a=rtpmap:109 opus/48000/2", "a=fmtp:109 stereo=0;useinbandfec=1")
        assertEquals(
            sdp("a=rtpmap:109 opus/48000/2", "a=fmtp:109 stereo=1;useinbandfec=1;sprop-stereo=1;maxaveragebitrate=128000"),
            OpusSdp.preferStereoMusic(input),
        )
    }

    @Test
    fun `leaves sdp without opus untouched`() {
        val input = sdp("m=video 9 UDP/TLS/RTP/SAVPF 96", "a=rtpmap:96 VP8/90000")
        assertEquals(input, OpusSdp.preferStereoMusic(input))
    }
}
