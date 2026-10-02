package com.mertbek.sharescreen.session

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.client.SignalingConnection
import com.mertbek.sharescreen.client.lanSignalingUrl
import com.mertbek.sharescreen.control.CONTROL_CHANNEL_LABEL
import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.link.ServerAddress
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.rtc.ControlChannel
import com.mertbek.sharescreen.rtc.IceCandidate
import com.mertbek.sharescreen.rtc.OpusSdp
import com.mertbek.sharescreen.rtc.PeerConnectionState
import com.mertbek.sharescreen.rtc.RemoteAudio
import com.mertbek.sharescreen.rtc.RemoteVideo
import com.mertbek.sharescreen.rtc.RtcEngine
import com.mertbek.sharescreen.rtc.RtcEvent
import com.mertbek.sharescreen.rtc.RtcException
import com.mertbek.sharescreen.rtc.RtcPeer
import com.mertbek.sharescreen.rtc.SdpType
import com.mertbek.sharescreen.rtc.SessionDescription
import com.mertbek.sharescreen.rtc.StreamInfo
import com.mertbek.sharescreen.settings.RememberedDevices
import com.mertbek.sharescreen.signaling.ErrorCode
import com.mertbek.sharescreen.signaling.IceServerConfig
import com.mertbek.sharescreen.signaling.PeerRole
import com.mertbek.sharescreen.signaling.SignalMessage
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class EndReason {
    CONNECTION_FAILED,
    INVALID_PIN,
    TOO_MANY_ATTEMPTS,
    REJECTED,
    ROOM_FULL,
    NO_SESSION,
    KICKED,
    HOST_ENDED,
    CONNECTION_LOST,
    UNSUPPORTED_VERSION,

    /** The host no longer remembers this device, which has forgotten the host too and can ask again. */
    PASS_REFUSED,
}

sealed interface ViewerState {
    data object Connecting : ViewerState
    data object WaitingForApproval : ViewerState
    data object Negotiating : ViewerState
    data class Watching(val video: RemoteVideo) : ViewerState
    data class Ended(val reason: EndReason) : ViewerState
}

sealed interface JoinTarget {
    val signalingUrl: String
    val roomCode: String?

    /** The [hostId] says which remembered host this is, when the viewer learned it from the host. */
    data class Lan(val host: String, val port: Int, val hostId: String? = null) : JoinTarget {
        override val signalingUrl get() = lanSignalingUrl(host, port)
        override val roomCode: String? get() = null
    }

    data class Internet(val server: String, override val roomCode: String) : JoinTarget {
        override val signalingUrl get() = ServerAddress.signalingUrl(server)
    }
}

data class ControlStatus(
    val available: Boolean = false,
    val role: ControlRole = ControlRole.NONE,
    val platform: HostPlatform = HostPlatform.ANDROID,
)

enum class ControlNotice { DENIED, ENDED }

class ViewerSession(
    private val rtc: RtcEngine,
    private val signalingClient: SignalingClient,
    private val deviceName: DeviceName,
    private val rememberedDevices: RememberedDevices? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Connecting)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _streamInfo = MutableStateFlow<StreamInfo?>(null)
    val streamInfo: StateFlow<StreamInfo?> = _streamInfo.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _control = MutableStateFlow(ControlStatus())
    val control: StateFlow<ControlStatus> = _control.asStateFlow()

    private val _controlNotices = MutableSharedFlow<ControlNotice>(extraBufferCapacity = 4)
    val controlNotices: SharedFlow<ControlNotice> = _controlNotices.asSharedFlow()

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private var controlChannel: ControlChannel? = null
    private var connection: SignalingConnection? = null
    private val unsent = ArrayDeque<SignalMessage>()
    private var resumeToken: String? = null
    private var recovery: Job? = null
    private var peer: RtcPeer? = null
    private var hostId: String? = null
    private var remoteVideo: RemoteVideo? = null
    private var remoteAudio: RemoteAudio? = null
    private var isConnected = false
    private var iceServers: List<IceServerConfig> = emptyList()
    private var passHost: String? = null

    fun connect(target: JoinTarget, pin: String?) {
        scope.launch { run(target, pin) }
    }

    fun setMuted(muted: Boolean) {
        scope.launch {
            _muted.value = muted
            remoteAudio?.setEnabled(!muted)
        }
    }

    fun requestControl() = sendControl(ControlMessage.Request)

    fun releaseControl() = sendControl(ControlMessage.Release)

    fun touch(time: Long, pointers: List<TouchPointer>) = sendWhenGranted(ControlMessage.Touch(time, pointers))

    fun pointer(message: ControlMessage.Pointer) = sendWhenGranted(message)

    fun keyboard(message: ControlMessage.Keyboard) = sendWhenGranted(message)

    fun navigate(action: NavAction) = sendControl(ControlMessage.Navigate(action))

    fun type(edit: ControlMessage.Type) = sendControl(edit)

    fun press(key: ControlKey) = sendControl(ControlMessage.Press(key))

    fun close() {
        scope.launch { release() }.invokeOnCompletion { scope.cancel() }
    }

    private fun sendControl(message: ControlMessage) = scope.launch { controlChannel?.send(message) }

    /** Sends now, or once the connection is back while it is being resumed. */
    private fun send(message: SignalMessage) {
        connection?.send(message) ?: unsent.addLast(message)
    }

    private fun sendWhenGranted(message: ControlMessage) = scope.launch {
        if (_control.value.role == ControlRole.GRANTED) controlChannel?.send(message)
    }

    private fun handleControl(message: ControlMessage) {
        if (message is ControlMessage.Remember) rememberedDevices?.rememberHost(message)
        if (message !is ControlMessage.Status) return
        val previous = _control.value
        _control.value = ControlStatus(message.available, message.role, message.platform)
        if (message.role == ControlRole.NONE) {
            when (previous.role) {
                ControlRole.REQUESTED -> if (message.available) _controlNotices.tryEmit(ControlNotice.DENIED)
                ControlRole.GRANTED -> _controlNotices.tryEmit(ControlNotice.ENDED)
                ControlRole.NONE -> Unit
            }
        }
    }

    private suspend fun run(target: JoinTarget, pin: String?) {
        val pass = (target as? JoinTarget.Lan)?.hostId?.let { id ->
            rememberedDevices?.passFor(id)?.also { passHost = id }
        }
        var connection = try {
            withTimeout(CONNECT_TIMEOUT) {
                signalingClient.connect(
                    target.signalingUrl,
                    SignalMessage.Hello(PeerRole.VIEWER, deviceName.value, pin = pin, roomCode = target.roomCode, pass = pass),
                )
            }
        } catch (_: TimeoutCancellationException) {
            end(EndReason.CONNECTION_FAILED)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not connect to ${target.signalingUrl}", e)
            end(EndReason.CONNECTION_FAILED)
            return
        }
        while (true) {
            this.connection = connection
            while (unsent.isNotEmpty()) connection.send(unsent.removeFirst())
            try {
                connection.messages.collect(::handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Signaling connection failed", e)
            }
            this.connection = null
            runCatching { connection.close() }
            if (_state.value is ViewerState.Ended) return
            connection = resume(target) ?: break
        }
        if (_state.value !is ViewerState.Watching) end(EndReason.CONNECTION_LOST)
    }

    private suspend fun resume(target: JoinTarget): SignalingConnection? {
        val deadline = TimeSource.Monotonic.markNow() + RESUME_WINDOW
        var wait = RESUME_FIRST_RETRY
        while (deadline.hasNotPassedNow()) {
            val token = resumeToken ?: return null
            if (_state.value is ViewerState.Ended) return null
            try {
                return withTimeout(CONNECT_TIMEOUT) {
                    signalingClient.connect(
                        target.signalingUrl,
                        SignalMessage.Hello(PeerRole.VIEWER, deviceName.value, roomCode = target.roomCode, resumeToken = token),
                    )
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
            } catch (e: Exception) {
                Log.d(TAG, "Could not resume yet: ${e.message}")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(RESUME_MAX_RETRY)
        }
        return null
    }

    private suspend fun handle(message: SignalMessage) {
        when (message) {
            is SignalMessage.Welcome -> {
                resumeToken = message.resumeToken
                if (!message.resumed) {
                    hostId = message.hostId
                    iceServers = message.iceServers
                    _state.value = ViewerState.WaitingForApproval
                }
            }
            is SignalMessage.JoinDecision -> if (message.accepted) {
                _state.value = ViewerState.Negotiating
                startPeer()
            }
            is SignalMessage.Offer -> answer(message)
            is SignalMessage.Ice ->
                peer?.addRemoteIceCandidate(IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate))
            is SignalMessage.SessionEnded -> end(EndReason.HOST_ENDED)
            is SignalMessage.Error -> {
                if (message.code == ErrorCode.RESUME_FAILED) resumeToken = null
                val passHost = passHost
                if (message.code == ErrorCode.REJECTED && passHost != null) {
                    // Only a pass the host does not know is refused without asking anyone.
                    rememberedDevices?.forgetHost(passHost)
                    end(EndReason.PASS_REFUSED)
                    return
                }
                val reason = message.code.toEndReason()
                if (reason != null) end(reason) else Log.w(TAG, "Signaling error ${message.code}: ${message.message}")
            }
            else -> Unit
        }
    }

    private fun startPeer() {
        val peer = rtc.createPeer(iceServers).also { peer = it }
        scope.launch { forwardEvents(peer) }
        scope.launch { pollStreamInfo(peer) }
    }

    private suspend fun pollStreamInfo(peer: RtcPeer) {
        while (!peer.isClosed) {
            delay(STREAM_INFO_INTERVAL)
            _streamInfo.value = peer.stats() ?: continue
        }
    }

    private suspend fun answer(offer: SignalMessage.Offer) {
        val peer = peer ?: return
        try {
            peer.setRemoteDescription(SessionDescription(SdpType.OFFER, offer.sdp))
            val answer = peer.createAnswer().let { it.copy(sdp = OpusSdp.preferStereoMusic(it.sdp)) }
            peer.setLocalDescription(answer)
            send(SignalMessage.Answer(to = offer.from, sdp = answer.sdp))
        } catch (e: RtcException) {
            Log.e(TAG, "Negotiation failed", e)
            end(EndReason.CONNECTION_FAILED)
        }
    }

    private suspend fun forwardEvents(peer: RtcPeer) {
        peer.events.collect { event ->
            when (event) {
                is RtcEvent.LocalIceCandidate -> {
                    val hostId = hostId ?: return@collect
                    send(
                        SignalMessage.Ice(
                            to = hostId,
                            candidate = event.candidate.candidate,
                            sdpMid = event.candidate.sdpMid,
                            sdpMLineIndex = event.candidate.sdpMLineIndex,
                        )
                    )
                }
                is RtcEvent.RemoteVideoTrack -> {
                    remoteVideo = event.track
                    showVideoIfReady()
                }
                is RtcEvent.RemoteAudioTrack -> {
                    remoteAudio = event.track
                    event.track.setEnabled(!_muted.value)
                    _hasAudio.value = true
                }
                is RtcEvent.RemoteDataChannel -> if (event.channel.label == CONTROL_CHANNEL_LABEL && controlChannel == null) {
                    val channel = ControlChannel(event.channel)
                    controlChannel = channel
                    scope.launch { channel.messages.collect(::handleControl) }
                }
                is RtcEvent.ConnectionState -> when (event.state) {
                    PeerConnectionState.CONNECTED -> {
                        isConnected = true
                        recovery?.cancel()
                        recovery = null
                        _reconnecting.value = false
                        showVideoIfReady()
                    }
                    PeerConnectionState.DISCONNECTED -> awaitRecovery()
                    PeerConnectionState.FAILED -> if (isConnected) awaitRecovery() else end(EndReason.CONNECTION_LOST)
                    else -> Unit
                }
            }
        }
    }

    private fun awaitRecovery() {
        if (!isConnected || recovery != null) return
        _reconnecting.value = true
        recovery = scope.launch {
            delay(RECOVERY_TIMEOUT)
            recovery = null
            end(EndReason.CONNECTION_LOST)
        }
    }

    private fun showVideoIfReady() {
        val video = remoteVideo ?: return
        if (isConnected && _state.value !is ViewerState.Ended) _state.value = ViewerState.Watching(video)
    }

    private suspend fun end(reason: EndReason) {
        if (_state.value is ViewerState.Ended) return
        _state.value = ViewerState.Ended(reason)
        release()
    }

    private suspend fun release() {
        remoteVideo = null
        remoteAudio = null
        _hasAudio.value = false
        _streamInfo.value = null
        controlChannel?.close()
        controlChannel = null
        _control.value = ControlStatus()
        recovery?.cancel()
        recovery = null
        _reconnecting.value = false
        peer?.close()
        peer = null
        connection?.let { runCatching { it.leave() } }
        connection = null
        unsent.clear()
        resumeToken = null
    }

    private fun ErrorCode.toEndReason(): EndReason? = when (this) {
        ErrorCode.INVALID_PIN -> EndReason.INVALID_PIN
        ErrorCode.TOO_MANY_ATTEMPTS -> EndReason.TOO_MANY_ATTEMPTS
        ErrorCode.REJECTED -> EndReason.REJECTED
        ErrorCode.ROOM_FULL -> EndReason.ROOM_FULL
        ErrorCode.ROOM_NOT_FOUND -> EndReason.NO_SESSION
        ErrorCode.KICKED -> EndReason.KICKED
        ErrorCode.UNSUPPORTED_VERSION -> EndReason.UNSUPPORTED_VERSION
        ErrorCode.UNAUTHORIZED, ErrorCode.HOST_EXISTS -> EndReason.CONNECTION_FAILED
        ErrorCode.PROTOCOL, ErrorCode.RESUME_FAILED -> null
    }

    private companion object {
        const val TAG = "ViewerSession"
        val CONNECT_TIMEOUT = 8.seconds
        val STREAM_INFO_INTERVAL = 2.seconds
        val RESUME_WINDOW = 25.seconds
        val RESUME_FIRST_RETRY = 1.seconds
        val RESUME_MAX_RETRY = 5.seconds
        val RECOVERY_TIMEOUT = 30.seconds
    }
}
