package com.mertbek.sharescreen.link

const val PIN_LENGTH = 6
const val ROOM_CODE_LENGTH = 6

fun isIpv4Address(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255
    }
}

fun isValidPin(value: String): Boolean = value.length == PIN_LENGTH && value.all(Char::isDigit)

fun isValidRoomCode(value: String): Boolean = value.length == ROOM_CODE_LENGTH && value.all(Char::isLetterOrDigit)

sealed interface ConnectLink {
    val pin: String?
    val name: String?
    val address: String

    fun toUri(): String

    /**
     * A local network link carries a PIN only when the sharing device asks for one, and the
     * sharing device's [hostId] so a device it remembers can come back without asking.
     */
    data class Lan(
        val host: String,
        val port: Int,
        override val pin: String?,
        override val name: String? = null,
        val hostId: String? = null,
    ) : ConnectLink {
        override val address get() = "$host:$port"

        override fun toUri(): String = uri(LAN_AUTHORITY, "h" to host, "p" to port.toString(), "pin" to pin, "n" to name, "i" to hostId)
    }

    data class Internet(
        val server: String,
        val roomCode: String,
        override val pin: String,
        override val name: String? = null,
    ) : ConnectLink {
        override val address get() = "$roomCode @ ${parseUri(server)?.host.orEmpty()}"

        override fun toUri(): String = uri(INTERNET_AUTHORITY, "s" to server, "r" to roomCode, "pin" to pin, "n" to name)

        fun toWebUri(webApp: String): String? {
            if (parseUri(server)?.scheme != "wss") return null
            return webApp.substringBefore('#') + "#" +
                listOf("s" to server.removePrefix("wss://"), "r" to roomCode, "p" to pin, "n" to name)
                    .filter { it.second != null }
                    .joinToString("&") { (key, value) -> "$key=${encodeComponent(value.orEmpty())}" }
        }
    }

    companion object {
        const val SCHEME = "sharescreen"
        const val LAN_AUTHORITY = "connect"
        const val INTERNET_AUTHORITY = "join"
        const val WEB_PATH = "/join"
        private const val MAX_HOST_ID_LENGTH = 64

        fun parse(value: String): ConnectLink? {
            val uri = parseUri(value.trim()) ?: return null
            if (uri.scheme.equals("https", ignoreCase = true)) return parseWeb(uri)
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val params = parseParameters(uri.rawQuery)
            val pin = params["pin"]
            if (pin != null && !isValidPin(pin)) return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }
            return when (uri.host) {
                LAN_AUTHORITY -> {
                    val host = params["h"]?.takeIf(::isPrivateIpv4) ?: return null
                    val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    Lan(host, port, pin, name, params["i"]?.takeIf { it.isNotBlank() && it.length <= MAX_HOST_ID_LENGTH })
                }
                INTERNET_AUTHORITY -> {
                    val server = params["s"]?.let(ServerAddress::normalize) ?: return null
                    val roomCode = params["r"]?.uppercase()?.takeIf(::isValidRoomCode) ?: return null
                    Internet(server, roomCode, pin ?: return null, name)
                }
                else -> null
            }
        }

        private fun parseWeb(uri: ParsedUri): ConnectLink? {
            if (uri.rawAuthority.isNullOrEmpty()) return null
            val params = parseParameters(uri.rawFragment)
            val roomCode = params["r"]?.uppercase()?.takeIf(::isValidRoomCode) ?: return null
            val pin = params["p"]?.takeIf(::isValidPin) ?: return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }
            val server = when {
                "s" in params -> ServerAddress.normalize(params.getValue("s"))
                uri.path == WEB_PATH -> ServerAddress.normalize("wss://${uri.rawAuthority}")
                else -> null
            } ?: return null
            return Internet(server, roomCode, pin, name)
        }

        private fun uri(authority: String, vararg params: Pair<String, String?>): String =
            "$SCHEME://$authority?" + params
                .filter { it.second != null }
                .joinToString("&") { (key, value) -> "$key=${encodeComponent(value.orEmpty())}" }
    }
}
