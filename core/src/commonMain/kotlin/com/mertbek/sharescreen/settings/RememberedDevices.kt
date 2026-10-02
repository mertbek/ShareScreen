package com.mertbek.sharescreen.settings

import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.signaling.DevicePass
import com.mertbek.sharescreen.signaling.hmacSha256
import com.mertbek.sharescreen.signaling.secureRandomBytes
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * A viewer this device lets in without asking when it shares on the local network, and with
 * [control] also lets take control without asking.
 */
@Serializable
data class RememberedViewer(
    val key: String,
    val name: String,
    val secret: String,
    val counter: Long = 0,
    val control: Boolean = false,
)

@Serializable
private data class RememberedHost(val hostId: String, val key: String, val secret: String, val counter: Long = 0)

/**
 * Devices that skip the PIN and the approval on the local network. A host hands a viewer a key and a
 * secret over the encrypted media connection. Coming back, the viewer signs a counter with the secret
 * instead of sending it, and the host takes each counter once, so a copied pass does not work again.
 */
class RememberedDevices(private val store: Settings) {

    /** Tells the viewers this device remembers which host it is. */
    val hostId: String = store.getStringOrNull(HOST_ID) ?: newToken(ID_BYTES).also { store.putString(HOST_ID, it) }

    private val _viewers = MutableStateFlow(read(VIEWERS, RememberedViewer.serializer()))
    val viewers: StateFlow<List<RememberedViewer>> = _viewers.asStateFlow()

    private val hosts = MutableStateFlow(read(HOSTS, RememberedHost.serializer()))

    /** Remembers a viewer and returns what to hand it. */
    fun rememberViewer(name: String, control: Boolean = false): ControlMessage.Remember {
        val viewer = RememberedViewer(key = newToken(ID_BYTES), name = name, secret = newToken(SECRET_BYTES), control = control)
        _viewers.update { it + viewer }
        saveViewers()
        return ControlMessage.Remember(hostId, viewer.key, viewer.secret)
    }

    fun allowControl(key: String, allowed: Boolean) {
        _viewers.update { list -> list.map { if (it.key == key) it.copy(control = allowed) else it } }
        saveViewers()
    }

    fun controlAllowed(key: String): Boolean = _viewers.value.any { it.key == key && it.control }

    fun forgetViewer(key: String) {
        _viewers.update { list -> list.filterNot { it.key == key } }
        saveViewers()
    }

    /** The remembered viewer behind a pass, or null when the pass is unknown, forged or used before. */
    fun admit(pass: DevicePass): RememberedViewer? {
        var admitted: RememberedViewer? = null
        _viewers.update { list ->
            val viewer = list.find { it.key == pass.key }
            admitted = viewer?.takeIf { pass.counter > it.counter && sameText(pass.proof, sign(it.secret, it.key, pass.counter)) }
            if (admitted == null) list else list.map { if (it.key == pass.key) it.copy(counter = pass.counter) else it }
        }
        if (admitted != null) saveViewers()
        return admitted
    }

    /** Keeps what a host handed this device, replacing anything that host handed it before. */
    fun rememberHost(invitation: ControlMessage.Remember) {
        val host = RememberedHost(invitation.hostId, invitation.key, invitation.secret)
        hosts.update { list -> list.filterNot { it.hostId == host.hostId } + host }
        saveHosts()
    }

    fun knowsHost(hostId: String?): Boolean = hostId != null && hosts.value.any { it.hostId == hostId }

    /** A pass for a host that remembers this device, each one with a higher counter. */
    fun passFor(hostId: String): DevicePass? {
        var pass: DevicePass? = null
        hosts.update { list ->
            val host = list.find { it.hostId == hostId }
            val counter = (host?.counter ?: 0) + 1
            pass = host?.let { DevicePass(it.key, counter, sign(it.secret, it.key, counter)) }
            if (host == null) list else list.map { if (it.hostId == hostId) it.copy(counter = counter) else it }
        }
        if (pass != null) saveHosts()
        return pass
    }

    fun forgetHost(hostId: String) {
        hosts.update { list -> list.filterNot { it.hostId == hostId } }
        saveHosts()
    }

    private fun saveViewers() = store.putString(VIEWERS, json.encodeToString(ListSerializer(RememberedViewer.serializer()), _viewers.value))

    private fun saveHosts() = store.putString(HOSTS, json.encodeToString(ListSerializer(RememberedHost.serializer()), hosts.value))

    private fun <T> read(name: String, serializer: KSerializer<T>): List<T> {
        val text = store.getStringOrNull(name) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), text) }.getOrDefault(emptyList())
    }

    private companion object {
        const val HOST_ID = "host_id"
        const val VIEWERS = "remembered_viewers"
        const val HOSTS = "remembered_hosts"
        const val ID_BYTES = 16
        const val SECRET_BYTES = 32

        val json = Json { ignoreUnknownKeys = true }
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        fun newToken(bytes: Int): String = encoder.encode(secureRandomBytes(bytes))

        fun sign(secret: String, key: String, counter: Long): String =
            encoder.encode(hmacSha256(secret.encodeToByteArray(), "$key:$counter".encodeToByteArray()))

        /** Compares without stopping at the first difference, so timing does not give a proof away. */
        fun sameText(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var difference = 0
            for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
            return difference == 0
        }
    }
}
