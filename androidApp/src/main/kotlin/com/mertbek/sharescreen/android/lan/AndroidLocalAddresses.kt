package com.mertbek.sharescreen.android.lan

import com.mertbek.sharescreen.platform.LocalAddressProvider
import java.net.Inet4Address
import java.net.NetworkInterface

class AndroidLocalAddresses : LocalAddressProvider {

    override fun ipv4Addresses(): List<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        return interfaces
            .filter { it.isUp && !it.isLoopback && lanRank(it.name) != null }
            .sortedBy { lanRank(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }

    private fun lanRank(interfaceName: String): Int? =
        LAN_INTERFACE_PREFIXES.indexOfFirst { interfaceName.startsWith(it) }.takeIf { it >= 0 }

    private companion object {
        val LAN_INTERFACE_PREFIXES = listOf("wlan", "swlan", "ap", "eth", "rndis")
    }
}
