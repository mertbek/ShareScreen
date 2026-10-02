package com.mertbek.sharescreen.android.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.mertbek.sharescreen.platform.DiscoveredHost
import com.mertbek.sharescreen.platform.LanBrowser
import com.mertbek.sharescreen.platform.LocalAddressProvider
import com.mertbek.sharescreen.signaling.PROTOCOL_VERSION
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.concurrent.Executors

class NsdBrowser(
    context: Context,
    private val localAddresses: LocalAddressProvider,
) : LanBrowser {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    private var job: Job? = null

    override val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    @Synchronized
    override fun start() {
        job?.cancel()
        job = scope.launch { discover().collect { _hosts.value = it } }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        _hosts.value = emptyList()
    }

    private fun discover(): Flow<List<DiscoveredHost>> = callbackFlow {
        val hosts = LinkedHashMap<String, DiscoveredHost>()
        var ownAddresses = emptySet<String>()
        executor.execute { ownAddresses = localAddresses.ipv4Addresses().toSet() }

        fun publish() {
            trySend(hosts.values.sortedBy { it.name.lowercase() })
        }

        fun onResolved(serviceName: String, info: NsdServiceInfo) {
            val host = info.toDiscoveredHost()
            if (host == null || host.host in ownAddresses) hosts.remove(serviceName) else hosts[serviceName] = host
            publish()
        }

        fun onLost(serviceName: String) {
            if (hosts.remove(serviceName) != null) publish()
        }

        val resolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CallbackResolver(::onResolved, ::onLost)
        } else {
            QueueResolver(::onResolved)
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = executor.execute { resolver.resolve(info) }

            override fun onServiceLost(info: NsdServiceInfo) = executor.execute {
                resolver.cancel(info.serviceName)
                onLost(info.serviceName)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        publish()
        nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            executor.execute { resolver.cancelAll() }
        }
    }

    private interface Resolver {
        fun resolve(info: NsdServiceInfo)
        fun cancel(serviceName: String)
        fun cancelAll()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private inner class CallbackResolver(
        private val onResolved: (String, NsdServiceInfo) -> Unit,
        private val onLost: (String) -> Unit,
    ) : Resolver {
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        override fun resolve(info: NsdServiceInfo) {
            val name = info.serviceName
            if (name in callbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) = onResolved(name, info)
                override fun onServiceLost() = onLost(name)
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    callbacks.remove(name)
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[name] = callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsd.registerServiceInfoCallback(info, executor, callback)
            }
        }

        override fun cancel(serviceName: String) {
            val callback = callbacks.remove(serviceName) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { nsd.unregisterServiceInfoCallback(callback) }
            }
        }

        override fun cancelAll() = callbacks.keys.toList().forEach(::cancel)
    }

    private inner class QueueResolver(
        private val onResolved: (String, NsdServiceInfo) -> Unit,
    ) : Resolver {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private var cancelled = false

        override fun resolve(info: NsdServiceInfo) {
            queue.addLast(info)
            next()
        }

        override fun cancel(serviceName: String) {
            queue.removeAll { it.serviceName == serviceName }
        }

        override fun cancelAll() {
            cancelled = true
            queue.clear()
        }

        private fun next() {
            if (busy || cancelled) return
            val info = queue.removeFirstOrNull() ?: return
            busy = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) = executor.execute {
                    busy = false
                    if (!cancelled) onResolved(info.serviceName, resolved)
                    next()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = executor.execute {
                    busy = false
                    next()
                }
            })
        }
    }

    private companion object {
        const val TAG = "NsdBrowser"

        fun NsdServiceInfo.toDiscoveredHost(): DiscoveredHost? {
            val version = attributes[NSD_ATTRIBUTE_PROTOCOL]?.decodeToString()?.toIntOrNull()
            if (version != null && version != PROTOCOL_VERSION) return null
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hostAddresses
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(host)
            }
            val address = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return null
            val pinRequired = attributes[NSD_ATTRIBUTE_PIN]?.decodeToString() != "0"
            val id = attributes[NSD_ATTRIBUTE_ID]?.decodeToString()?.takeIf { it.isNotBlank() }
            return DiscoveredHost(name = serviceName, host = address, port = port, pinRequired = pinRequired, id = id)
        }
    }
}
