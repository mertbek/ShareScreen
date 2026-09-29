package com.mertbek.sharescreen.link

import com.mertbek.sharescreen.signaling.SIGNALING_PATH

object ServerAddress {

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val schemeEnd = trimmed.indexOf("://")
        val scheme = if (schemeEnd < 0) null else trimmed.substring(0, schemeEnd).lowercase()
        val rest = (if (schemeEnd < 0) trimmed else trimmed.substring(schemeEnd + 3)).trimEnd('/')
        if (rest.isEmpty()) return null
        val webSocketScheme = when (scheme) {
            null, "wss", "https" -> "wss"
            "ws", "http" -> "ws"
            else -> return null
        }
        val address = "$webSocketScheme://$rest"
        val uri = parseUri(address) ?: return null
        return address.takeIf { !uri.host.isNullOrEmpty() }
    }

    fun signalingUrl(server: String): String = server + SIGNALING_PATH
}
