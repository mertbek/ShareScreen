package com.mertbek.sharescreen.session

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.client.SignalingConnection
import com.mertbek.sharescreen.client.lanSignalingUrl
import com.mertbek.sharescreen.control.CONTROL_CHANNEL_LABEL
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.link.ServerAddress
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.platform.InputInjector
import com.mertbek.sharescreen.platform.LanAdvertiser
import com.mertbek.sharescreen.platform.LanServer
import com.mertbek.sharescreen.platform.LocalAddressProvider
import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.rtc.ControlChannel
import com.mertbek.sharescreen.rtc.IceCandidate
import com.mertbek.sharescreen.rtc.OpusSdp
import com.mertbek.sharescreen.rtc.PeerConnectionState
import com.mertbek.sharescreen.rtc.RtcEngine
import com.mertbek.sharescreen.rtc.RtcEvent
import com.mertbek.sharescreen.rtc.RtcException
import com.mertbek.sharescreen.rtc.RtcPeer
import com.mertbek.sharescreen.rtc.SdpType
import com.mertbek.sharescreen.rtc.SessionDescription
import com.mertbek.sharescreen.signaling.ErrorCode
import com.mertbek.sharescreen.signaling.IceServerConfig
import com.mertbek.sharescreen.signaling.PeerRole
import com.mertbek.sharescreen.signaling.SignalMessage
import com.mertbek.sharescreen.signaling.secureRandomBytes
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class HostSession(
    private val rtc: RtcEngine,
    private val signalingClient: SignalingClient,
    private val deviceName: DeviceName,
    private val lanServer: LanServer? = null,
    private val localAddresses: LocalAddressProvider? = null,
    private val lanAdvertiser: LanAdvertiser? = null,
    private val inputInjector: InputInjector? = null,
) {
    private class Link(var connection: SignalingConnection, val viaInternet: Boolean) {
        var iceServers: List<IceServerConfig> = emptyList()
        var resumeToken: String? = null
        var isConnected = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private var sessionScope: CoroutineScope? = null
    private var media: CapturedMedia? = null
    private var lanLink: Link? = null
    private var internetLink: Link? = null
    private val viewerLinks = HashMap<String, Link>()
    private val peers = HashMap<String, RtcPeer>()
    private val controlChannels = HashMap<String, ControlChannel>()
    private val recoveries = HashMap<String, Job>()
    private val connectedOnce = HashSet<String>()
    private var allowControl = false
    private var controlAvailable = false

    fun start(media: CapturedMedia, internetServer: String?, allowControl: Boolean) {
        scope.launch {
            if (sessionScope != null) return@launch
            val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
            sessionScope = session
            this@HostSession.media = media
            this@HostSession.allowControl = allowControl && inputInjector != null
            controlAvailable = false
            session.launch { run(internetServer) }
            if (this@HostSession.allowControl) session.launch { followControlAvailability() }
        }
    }

    fun stop(): Job = scope.launch {
        sessionScope?.cancel()
        sessionScope = null
        if ((_state.value as? HostState.Live)?.controller != null) inputInjector?.releaseInput()
        controlChannels.values.forEach(ControlChannel::close)
        controlChannels.clear()
        recoveries.clear()
        connectedOnce.clear()
        peers.values.forEach(RtcPeer::close)
        peers.clear()
        viewerLinks.clear()
        lanAdvertiser?.unregister()
        for (link in listOfNotNull(lanLink, internetLink)) runCatching { link.connection.leave() }
        lanLink = null
        internetLink = null
        media = null
        lanServer?.stop()
        _state.value = HostState.Idle
    }

    fun approve(viewerId: String) = launchInSession {
        val pending = takePending(viewerId) ?: return@launchInSession
        val link = viewerLinks[viewerId] ?: return@launchInSession
        val media = media ?: return@launchInSession

        link.connection.send(SignalMessage.JoinDecision(viewerId, accepted = true))
        val peer = rtc.createPeer(link.iceServers)
        peers[viewerId] = peer
        updateLive {
            it.copy(viewers = it.viewers + ViewerInfo(viewerId, pending.deviceName, isConnected = false, viaInternet = link.viaInternet))
        }
        launch { forwardEvents(viewerId, peer, link) }

        try {
            peer.addMedia(media)
            val control = ControlChannel(peer.createDataChannel(CONTROL_CHANNEL_LABEL))
            controlChannels[viewerId] = control
            launch { if (control.awaitOpen()) control.send(controlStatus(viewerId)) }
            launch { control.messages.collect { onControlMessage(viewerId, it) } }
            val offer = peer.createOffer().let { it.copy(sdp = OpusSdp.preferStereoMusic(it.sdp)) }
            peer.setLocalDescription(offer)
            link.connection.send(SignalMessage.Offer(to = viewerId, sdp = offer.sdp))
        } catch (e: RtcException) {
            Log.e(TAG, "Could not negotiate with $viewerId", e)
            disconnect(viewerId)
        }
    }

    fun reject(viewerId: String) = launchInSession {
        takePending(viewerId) ?: return@launchInSession
        viewerLinks.remove(viewerId)?.connection?.send(SignalMessage.JoinDecision(viewerId, accepted = false))
    }

    fun kick(viewerId: String) = launchInSession { disconnect(viewerId) }

    fun grantControl(viewerId: String) = launchInSession {
        if (!controlAvailable || controlRole(viewerId) != ControlRole.REQUESTED) return@launchInSession
        (_state.value as? HostState.Live)?.controller?.let { setControlRole(it.id, ControlRole.NONE) }
        setControlRole(viewerId, ControlRole.GRANTED)
    }

    fun denyControl(viewerId: String) = launchInSession {
        if (controlRole(viewerId) == ControlRole.REQUESTED) setControlRole(viewerId, ControlRole.NONE)
    }

    fun revokeControl() = launchInSession {
        (_state.value as? HostState.Live)?.controller?.let { setControlRole(it.id, ControlRole.NONE) }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun run(internetServer: String?) {
        _state.value = HostState.Starting
        val hostSecret = Uuid.random().toString()
        val pin = randomPin()
        try {
            if (lanServer == null && internetServer == null) error("No way to accept viewers")
            var port = 0
            var lan: Link? = null
            if (lanServer != null) {
                port = lanServer.start(hostSecret)
                lan = Link(
                    signalingClient.connect(
                        lanSignalingUrl(LOOPBACK, port),
                        SignalMessage.Hello(PeerRole.HOST, deviceName.value, pin = pin, hostSecret = hostSecret),
                    ),
                    viaInternet = false,
                )
                lanLink = lan
                withTimeout(LAN_WELCOME_TIMEOUT) { handle(lan, lan.connection.messages.first()) }
            }
            _state.value = HostState.Live(
                deviceName = deviceName.value,
                addresses = localAddresses?.ipv4Addresses().orEmpty(),
                port = port,
                pin = pin,
                internetRoom = internetServer?.let(InternetRoom::Connecting),
                remoteControl = remoteControlAvailability(),
            )
            if (lan != null) lanAdvertiser?.register(deviceName.value, port)
            coroutineScope {
                val internet = internetServer?.let { launch { runInternet(it, pin) } }
                if (lan != null) {
                    lan.connection.messages.collect { handle(lan, it) }
                    internet?.cancel()
                } else {
                    internet?.join()
                    awaitCancellation()
                }
            }
            _state.value = HostState.Failed("Signaling connection closed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Host session failed", e)
            _state.value = HostState.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun runInternet(server: String, pin: String) {
        val link = try {
            val connection = withTimeout(INTERNET_CONNECT_TIMEOUT) {
                signalingClient.connect(
                    ServerAddress.signalingUrl(server),
                    SignalMessage.Hello(PeerRole.HOST, deviceName.value, pin = pin),
                )
            }
            Link(connection, viaInternet = true)
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Timed out connecting to $server")
            updateLive { it.copy(internetRoom = InternetRoom.Failed(server)) }
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach $server", e)
            updateLive { it.copy(internetRoom = InternetRoom.Failed(server)) }
            return
        }
        internetLink = link
        while (true) {
            try {
                link.connection.messages.collect { handle(link, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Internet signaling failed", e)
            }
            link.isConnected = false
            runCatching { link.connection.close() }
            updateLive { live ->
                val room = live.internetRoom as? InternetRoom.Open ?: return@updateLive live
                live.copy(internetRoom = room.copy(reconnecting = true))
            }
            link.connection = resumeInternet(server, link) ?: break
            link.isConnected = true
        }
        internetLink = null
        val pendingIds = (_state.value as? HostState.Live)?.pendingViewers.orEmpty().map { it.id }.toSet()
        viewerLinks.filter { (id, viewerLink) -> viewerLink === link && id in pendingIds }.keys.toList().forEach(::remove)
        updateLive { it.copy(internetRoom = InternetRoom.Closed(server)) }
    }

    private suspend fun resumeInternet(server: String, link: Link): SignalingConnection? {
        val deadline = TimeSource.Monotonic.markNow() + RESUME_WINDOW
        var wait = RESUME_FIRST_RETRY
        while (deadline.hasNotPassedNow()) {
            val token = link.resumeToken ?: return null
            try {
                return withTimeout(INTERNET_CONNECT_TIMEOUT) {
                    signalingClient.connect(
                        ServerAddress.signalingUrl(server),
                        SignalMessage.Hello(PeerRole.HOST, deviceName.value, resumeToken = token),
                    )
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
            } catch (e: Exception) {
                Log.d(TAG, "Could not reach $server yet: ${e.message}")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(RESUME_MAX_RETRY)
        }
        return null
    }

    private suspend fun handle(link: Link, message: SignalMessage) {
        when (message) {
            is SignalMessage.Welcome -> {
                link.resumeToken = message.resumeToken
                if (link.viaInternet) {
                    if (!message.resumed) link.iceServers = message.iceServers
                    updateLive { live ->
                        val server = live.internetRoom?.server ?: return@updateLive live
                        live.copy(internetRoom = InternetRoom.Open(server, message.roomCode))
                    }
                }
                if (message.resumed) {
                    recoveries.keys.filter { viewerLinks[it] === link }.forEach { restartIce(it) }
                }
            }
            is SignalMessage.Error if message.code == ErrorCode.RESUME_FAILED -> link.resumeToken = null
            is SignalMessage.JoinRequest -> {
                viewerLinks[message.viewerId] = link
                updateLive {
                    it.copy(pendingViewers = it.pendingViewers + PendingViewer(message.viewerId, message.deviceName, link.viaInternet))
                }
            }
            is SignalMessage.Answer -> withPeer(message.from) {
                if (!it.hasLocalOffer) return@withPeer
                it.setRemoteDescription(SessionDescription(SdpType.ANSWER, message.sdp))
            }
            is SignalMessage.Ice -> withPeer(message.from) {
                it.addRemoteIceCandidate(IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate))
            }
            is SignalMessage.PeerLeft -> remove(message.peerId)
            is SignalMessage.Error -> Log.w(TAG, "Signaling error ${message.code}: ${message.message}")
            else -> Unit
        }
    }

    private suspend fun forwardEvents(viewerId: String, peer: RtcPeer, link: Link) {
        peer.events.collect { event ->
            when (event) {
                is RtcEvent.LocalIceCandidate -> link.connection.send(
                    SignalMessage.Ice(
                        to = viewerId,
                        candidate = event.candidate.candidate,
                        sdpMid = event.candidate.sdpMid,
                        sdpMLineIndex = event.candidate.sdpMLineIndex,
                    )
                )
                is RtcEvent.ConnectionState -> {
                    val connected = event.state == PeerConnectionState.CONNECTED
                    updateLive { live ->
                        live.copy(viewers = live.viewers.map { if (it.id == viewerId) it.copy(isConnected = connected) else it })
                    }
                    when (event.state) {
                        PeerConnectionState.CONNECTED -> {
                            connectedOnce += viewerId
                            recoveries.remove(viewerId)?.cancel()
                        }
                        PeerConnectionState.DISCONNECTED -> recover(viewerId, failed = false)
                        PeerConnectionState.FAILED -> recover(viewerId, failed = true)
                        else -> Unit
                    }
                }
                is RtcEvent.RemoteVideoTrack, is RtcEvent.RemoteAudioTrack, is RtcEvent.RemoteDataChannel -> Unit
            }
        }
    }

    private fun recover(viewerId: String, failed: Boolean) {
        if (viewerId !in connectedOnce) {
            if (failed) disconnect(viewerId)
            return
        }
        if (viewerId in recoveries) {
            if (failed) sessionScope?.launch { restartIce(viewerId) }
            return
        }
        val session = sessionScope ?: return
        recoveries[viewerId] = session.launch {
            if (!failed) delay(RESTART_DELAY)
            restartIce(viewerId)
            delay(RECOVERY_TIMEOUT)
            Log.w(TAG, "$viewerId did not reconnect")
            recoveries.remove(viewerId)
            disconnect(viewerId)
        }
    }

    private suspend fun restartIce(viewerId: String) {
        val peer = peers[viewerId] ?: return
        val link = viewerLinks[viewerId] ?: return
        if (!link.isConnected) return
        try {
            if (peer.hasLocalOffer) peer.setLocalDescription(SessionDescription(SdpType.ROLLBACK, ""))
            peer.restartIce()
            val offer = peer.createOffer().let { it.copy(sdp = OpusSdp.preferStereoMusic(it.sdp)) }
            peer.setLocalDescription(offer)
            link.connection.send(SignalMessage.Offer(to = viewerId, sdp = offer.sdp))
            Log.i(TAG, "Restarting ICE with $viewerId")
        } catch (e: RtcException) {
            Log.w(TAG, "Could not restart ICE with $viewerId", e)
        }
    }

    private fun onControlMessage(viewerId: String, message: ControlMessage) {
        val role = controlRole(viewerId) ?: return
        val granted = role == ControlRole.GRANTED
        val injector = inputInjector
        when (message) {
            ControlMessage.Request ->
                if (controlAvailable && role == ControlRole.NONE) setControlRole(viewerId, ControlRole.REQUESTED)
            ControlMessage.Release -> if (role != ControlRole.NONE) setControlRole(viewerId, ControlRole.NONE)
            is ControlMessage.Touch -> if (granted) injector?.touch(message.time, message.pointers)
            is ControlMessage.Navigate -> if (granted) injector?.navigate(message.action)
            is ControlMessage.Type -> if (granted) injector?.type(message.deleteBefore, message.text)
            is ControlMessage.Press -> if (granted) injector?.press(message.key)
            is ControlMessage.Pointer -> if (granted) injector?.pointer(message)
            is ControlMessage.Keyboard -> if (granted) injector?.keyboard(message)
            is ControlMessage.Status -> Unit
        }
    }

    private suspend fun followControlAvailability() {
        val injector = inputInjector ?: return
        injector.isAvailable.collect { available ->
            controlAvailable = allowControl && available
            if (!controlAvailable) {
                (_state.value as? HostState.Live)?.viewers.orEmpty()
                    .filter { it.control != ControlRole.NONE }
                    .forEach { setControlRole(it.id, ControlRole.NONE) }
            }
            updateLive { it.copy(remoteControl = remoteControlAvailability()) }
            for ((id, channel) in controlChannels) channel.send(controlStatus(id))
        }
    }

    private fun remoteControlAvailability() = when {
        !allowControl -> RemoteControlAvailability.OFF
        controlAvailable -> RemoteControlAvailability.READY
        else -> RemoteControlAvailability.NEEDS_SERVICE
    }

    private fun setControlRole(viewerId: String, role: ControlRole) {
        if (controlRole(viewerId) == ControlRole.GRANTED && role != ControlRole.GRANTED) {
            inputInjector?.releaseInput()
        }
        updateLive { live ->
            live.copy(viewers = live.viewers.map { if (it.id == viewerId) it.copy(control = role) else it })
        }
        controlChannels[viewerId]?.send(controlStatus(viewerId))
    }

    private fun controlRole(viewerId: String): ControlRole? =
        (_state.value as? HostState.Live)?.viewers?.find { it.id == viewerId }?.control

    private fun controlStatus(viewerId: String) = ControlMessage.Status(
        available = controlAvailable,
        role = controlRole(viewerId) ?: ControlRole.NONE,
        platform = inputInjector?.platform ?: HostPlatform.ANDROID,
    )

    private suspend fun withPeer(viewerId: String, block: suspend (RtcPeer) -> Unit) {
        val peer = peers[viewerId] ?: return
        try {
            block(peer)
        } catch (e: RtcException) {
            Log.e(TAG, "WebRTC error with $viewerId", e)
            disconnect(viewerId)
        }
    }

    private fun disconnect(viewerId: String) {
        viewerLinks[viewerId]?.connection?.send(SignalMessage.Kick(viewerId))
        remove(viewerId)
    }

    private fun remove(viewerId: String) {
        if (controlRole(viewerId) == ControlRole.GRANTED) inputInjector?.releaseInput()
        viewerLinks.remove(viewerId)
        recoveries.remove(viewerId)?.cancel()
        connectedOnce -= viewerId
        controlChannels.remove(viewerId)?.close()
        peers.remove(viewerId)?.close()
        updateLive { live ->
            live.copy(
                viewers = live.viewers.filterNot { it.id == viewerId },
                pendingViewers = live.pendingViewers.filterNot { it.id == viewerId },
            )
        }
    }

    private fun takePending(viewerId: String): PendingViewer? {
        val pending = (_state.value as? HostState.Live)?.pendingViewers?.find { it.id == viewerId } ?: return null
        updateLive { it.copy(pendingViewers = it.pendingViewers - pending) }
        return pending
    }

    private fun updateLive(transform: (HostState.Live) -> HostState.Live) {
        _state.update { if (it is HostState.Live) transform(it) else it }
    }

    private fun launchInSession(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { sessionScope?.launch(block = block) }
    }

    private fun randomPin(): String {
        val bytes = secureRandomBytes(4)
        val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    private companion object {
        const val TAG = "HostSession"
        const val LOOPBACK = "127.0.0.1"
        val INTERNET_CONNECT_TIMEOUT = 10.seconds
        val LAN_WELCOME_TIMEOUT = 5.seconds
        val RESUME_WINDOW = 25.seconds
        val RESUME_FIRST_RETRY = 1.seconds
        val RESUME_MAX_RETRY = 5.seconds
        val RESTART_DELAY = 3.seconds
        val RECOVERY_TIMEOUT = 30.seconds
    }
}
