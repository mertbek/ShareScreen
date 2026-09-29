package com.mertbek.sharescreen.rtc

object OpusSdp {

    private val MUSIC_PARAMETERS = linkedMapOf(
        "stereo" to "1",
        "sprop-stereo" to "1",
        "maxaveragebitrate" to "128000",
    )

    private val OPUS_RTPMAP = Regex("""^a=rtpmap:(\d+) opus/48000/2$""", RegexOption.IGNORE_CASE)

    fun preferStereoMusic(sdp: String): String {
        val lines = sdp.split("\r\n")
        val payloadType = lines.firstNotNullOfOrNull { OPUS_RTPMAP.find(it)?.groupValues?.get(1) } ?: return sdp
        val prefix = "a=fmtp:$payloadType "
        return lines.joinToString("\r\n") { line ->
            if (!line.startsWith(prefix)) return@joinToString line
            val parameters = line.removePrefix(prefix)
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            prefix + (parameters + MUSIC_PARAMETERS).entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }
}
