package com.mertbek.sharescreen.lan

import com.mertbek.sharescreen.platform.LocalAddressProvider
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkAddresses : LocalAddressProvider {

    override fun ipv4Addresses(): List<String> = addresses().map { it.hostAddress }

    fun addresses(): List<Inet4Address> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
}
