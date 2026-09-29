package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.signaling.ErrorCode
import com.mertbek.sharescreen.signaling.Member
import com.mertbek.sharescreen.signaling.PeerLink
import com.mertbek.sharescreen.signaling.RoomManager
import com.mertbek.sharescreen.signaling.SIGNALING_PATH
import com.mertbek.sharescreen.signaling.SignalCodec
import com.mertbek.sharescreen.signaling.SignalMessage
import io.ktor.server.application.Application
import io.ktor.server.routing.application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

private val HELLO_TIMEOUT = 10.seconds
private const val OUTBOX_CAPACITY = 64

fun Application.signalingModule(roomManager: RoomManager) {
    installSignalingWebSockets()
    routing {
        signalingRoute(roomManager)
    }
}

fun Application.installSignalingWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 64 * 1024
    }
}

fun Route.signalingRoute(roomManager: RoomManager, path: String = SIGNALING_PATH) {
    application.launch { roomManager.expirePeriodically() }
    webSocket(path) {
        val link = ChannelPeerLink()
        val writer = launch {
            try {
                for (message in link.outbox) {
                    outgoing.send(Frame.Text(SignalCodec.encode(message)))
                }
                close(CloseReason(CloseReason.Codes.NORMAL, ""))
            } catch (_: Exception) {
            }
        }

        var member: Member? = null
        try {
            val hello = withTimeoutOrNull(HELLO_TIMEOUT) {
                (incoming.receiveCatching().getOrNull() as? Frame.Text)?.let { decodeOrNull(it.readText()) }
            }
            if (hello !is SignalMessage.Hello) {
                link.send(SignalMessage.Error(ErrorCode.PROTOCOL, "Expected hello"))
                return@webSocket
            }
            member = roomManager.join(link, hello) ?: return@webSocket

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = decodeOrNull(frame.readText())
                if (message == null) {
                    link.send(SignalMessage.Error(ErrorCode.PROTOCOL, "Malformed message"))
                    continue
                }
                roomManager.handle(member, message)
            }
        } finally {
            withContext(NonCancellable) {
                member?.let { roomManager.disconnected(it, link) }
                link.close()
                writer.join()
            }
        }
    }
}

private fun decodeOrNull(text: String): SignalMessage? =
    try {
        SignalCodec.decode(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private class ChannelPeerLink : PeerLink {
    val outbox = Channel<SignalMessage>(OUTBOX_CAPACITY)

    override fun send(message: SignalMessage) {
        if (outbox.trySend(message).isFailure) outbox.close()
    }

    override fun close() {
        outbox.close()
    }
}
