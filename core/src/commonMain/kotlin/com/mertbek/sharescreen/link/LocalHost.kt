package com.mertbek.sharescreen.link

fun isPrivateIpv4(value: String): Boolean {
    if (!isIpv4Address(value)) return false
    val parts = value.split('.').map { it.toInt() }
    return parts[0] == 10 ||
        parts[0] == 127 ||
        (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 192 && parts[1] == 168) ||
        (parts[0] == 169 && parts[1] == 254)
}

fun isLocalHostName(host: String): Boolean {
    val name = host.lowercase()
    return name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || isPrivateIpv4(name)
}
