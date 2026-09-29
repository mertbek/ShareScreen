package com.mertbek.sharescreen.link

internal class ParsedUri(
    val scheme: String,
    val rawAuthority: String?,
    val path: String,
    val rawQuery: String?,
    val rawFragment: String?,
) {
    val host: String?
        get() = rawAuthority
            ?.substringAfterLast('@')
            ?.substringBefore(':')
            ?.takeIf { it.isNotEmpty() }
}

private val uriPattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")

internal fun parseUri(value: String): ParsedUri? {
    if (value.any { it.isWhitespace() }) return null
    val match = uriPattern.matchEntire(value) ?: return null
    val groups = match.groups
    return ParsedUri(
        scheme = match.groupValues[1],
        rawAuthority = groups[2]?.value,
        path = match.groupValues[3],
        rawQuery = groups[4]?.value,
        rawFragment = groups[5]?.value,
    )
}

private const val UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"
private const val HEX = "0123456789ABCDEF"

internal fun encodeComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xFF
        val char = unsigned.toChar()
        when {
            unsigned < 0x80 && char in UNRESERVED -> append(char)
            char == ' ' -> append('+')
            else -> append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0xF])
        }
    }
}

internal fun decodeComponent(value: String): String? {
    val input = value.encodeToByteArray()
    val output = ArrayList<Byte>(input.size)
    var index = 0
    while (index < input.size) {
        val byte = input[index]
        when (byte.toInt().toChar()) {
            '+' -> output.add(' '.code.toByte())
            '%' -> {
                if (index + 2 >= input.size) return null
                val hex = input.decodeToString(index + 1, index + 3)
                output.add(hex.toIntOrNull(16)?.toByte() ?: return null)
                index += 2
            }
            else -> output.add(byte)
        }
        index++
    }
    return output.toByteArray().decodeToString()
}

internal fun parseParameters(encoded: String?): Map<String, String> = encoded.orEmpty()
    .split('&')
    .mapNotNull { parameter ->
        val parts = parameter.split('=', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val value = decodeComponent(parts[1]) ?: return@mapNotNull null
        parts[0] to value
    }
    .toMap()
