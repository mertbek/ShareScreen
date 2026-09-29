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
    val pin: String
    val name: String?
    val address: String

    fun toUri(): String

    data class Lan(val host: String, val port: Int, override val pin: String, override val name: String? = null) : ConnectLink {
        override val address get() = "$host:$port"

        override fun toUri(): String = uri(LAN_AUTHORITY, "h" to host, "p" to port.toString(), "pin" to pin, "n" to name)
    }

    data class Internet(
        val server: String,
        val roomCode: String,
        override val pin: String,
        override val name: String? = null,
    ) : ConnectLink {
        override val address get() = "$roomCode @ ${parseUri(server)?.host.orEmpty()}"

        override fun toUri(): String = uri(INTERNET_AUTHORITY, "s" to server, "r" to roomCode, "pin" to pin, "n" to name)

        fun toWebUri(): String? {
            val uri = parseUri(server)?.takeIf { it.scheme == "wss" } ?: return null
            return "https://${uri.rawAuthority}$WEB_PATH#" + listOf("r" to roomCode, "p" to pin, "n" to name)
                .filter { it.second != null }
                .joinToString("&") { (key, value) -> "$key=${encodeComponent(value.orEmpty())}" }
        }
    }

    companion object {
        const val SCHEME = "sharescreen"
        const val LAN_AUTHORITY = "connect"
        const val INTERNET_AUTHORITY = "join"
        const val WEB_PATH = "/join"

        fun parse(value: String): ConnectLink? {
            val uri = parseUri(value.trim()) ?: return null
            if (uri.scheme.equals("https", ignoreCase = true)) return parseWeb(uri)
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val params = parseParameters(uri.rawQuery)
            val pin = params["pin"]?.takeIf(::isValidPin) ?: return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }
            return when (uri.host) {
                LAN_AUTHORITY -> {
                    val host = params["h"]?.takeIf(::isPrivateIpv4) ?: return null
                    val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    Lan(host, port, pin, name)
                }
                INTERNET_AUTHORITY -> {
                    val server = params["s"]?.let(ServerAddress::normalize) ?: return null
                    val roomCode = params["r"]?.uppercase()?.takeIf(::isValidRoomCode) ?: return null
                    Internet(server, roomCode, pin, name)
                }
                else -> null
            }
        }

        private fun parseWeb(uri: ParsedUri): ConnectLink? {
            if (uri.path != WEB_PATH || uri.rawAuthority.isNullOrEmpty()) return null
            val params = parseParameters(uri.rawFragment)
            val roomCode = params["r"]?.uppercase()?.takeIf(::isValidRoomCode) ?: return null
            val pin = params["p"]?.takeIf(::isValidPin) ?: return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }
            val server = ServerAddress.normalize("wss://${uri.rawAuthority}") ?: return null
            return Internet(server, roomCode, pin, name)
        }

        private fun uri(authority: String, vararg params: Pair<String, String?>): String =
            "$SCHEME://$authority?" + params
                .filter { it.second != null }
                .joinToString("&") { (key, value) -> "$key=${encodeComponent(value.orEmpty())}" }
    }
}
