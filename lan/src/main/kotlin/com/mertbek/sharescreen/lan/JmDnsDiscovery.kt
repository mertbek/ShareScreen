package com.mertbek.sharescreen.lan

import com.mertbek.sharescreen.platform.DiscoveredHost
import com.mertbek.sharescreen.platform.LanAdvertiser
import com.mertbek.sharescreen.platform.LanBrowser
import com.mertbek.sharescreen.signaling.PROTOCOL_VERSION
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

private const val SERVICE_TYPE = "_sharescreen._tcp.local."
private const val ATTRIBUTE_PROTOCOL = "v"
private const val TAG = "JmDnsDiscovery"

class JmDnsAdvertiser(private val addresses: NetworkAddresses = NetworkAddresses()) : LanAdvertiser {
    private var instances = emptyList<JmDNS>()

    @Synchronized
    override fun register(name: String, port: Int) {
        unregister()
        val targets = addresses.addresses()
        thread(isDaemon = true, name = "mdns-register") {
            val created = targets.mapNotNull { address ->
                runCatching {
                    JmDNS.create(address, name).also {
                        it.registerService(
                            ServiceInfo.create(SERVICE_TYPE, name, port, 0, 0, mapOf(ATTRIBUTE_PROTOCOL to PROTOCOL_VERSION.toString()))
                        )
                    }
                }.onFailure { Log.w(TAG, "Could not announce on $address", it) }.getOrNull()
            }
            synchronized(this) { instances = instances + created }
        }
    }

    @Synchronized
    override fun unregister() {
        val current = instances
        instances = emptyList()
        if (current.isEmpty()) return
        thread(isDaemon = true, name = "mdns-unregister") {
            current.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        }
    }
}

class JmDnsBrowser(
    private val addresses: NetworkAddresses = NetworkAddresses(),
    private val excludeOwn: Boolean = true,
) : LanBrowser {
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    private var instances = emptyList<JmDNS>()

    override val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    @Synchronized
    override fun start() {
        stop()
        val own = addresses.ipv4Addresses().toSet()
        val targets = addresses.addresses()
        thread(isDaemon = true, name = "mdns-browse") {
            val created = targets.mapNotNull { address ->
                runCatching {
                    JmDNS.create(address).also { it.addServiceListener(SERVICE_TYPE, listener(it, own)) }
                }.onFailure { Log.w(TAG, "Could not browse on $address", it) }.getOrNull()
            }
            synchronized(this) { instances = instances + created }
        }
    }

    @Synchronized
    override fun stop() {
        val current = instances
        instances = emptyList()
        _hosts.value = emptyList()
        if (current.isEmpty()) return
        thread(isDaemon = true, name = "mdns-stop") {
            current.forEach { runCatching { it.close() } }
        }
    }

    private fun listener(jmdns: JmDNS, own: Set<String>) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns.requestServiceInfo(event.type, event.name)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            _hosts.update { list -> list.filterNot { it.name == event.name } }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val host = event.info.inet4Addresses.firstOrNull()?.hostAddress ?: return
            if (excludeOwn && host in own) return
            val found = DiscoveredHost(event.name, host, event.info.port)
            _hosts.update { list -> list.filterNot { it.name == found.name } + found }
        }
    }
}
