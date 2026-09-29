package com.mertbek.sharescreen.android.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mertbek.sharescreen.platform.LanAdvertiser
import com.mertbek.sharescreen.signaling.PROTOCOL_VERSION
import com.mertbek.sharescreen.util.Log

internal const val NSD_SERVICE_TYPE = "_sharescreen._tcp"
internal const val NSD_ATTRIBUTE_PROTOCOL = "v"

class NsdAdvertiser(context: Context) : LanAdvertiser {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    override fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = NSD_SERVICE_TYPE
            this.port = port
            setAttribute(NSD_ATTRIBUTE_PROTOCOL, PROTOCOL_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        this.listener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    override fun unregister() {
        val listener = listener ?: return
        this.listener = null
        runCatching { nsd.unregisterService(listener) }
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
    }
}
