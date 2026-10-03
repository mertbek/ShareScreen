package com.mertbek.sharescreen.client

import com.mertbek.sharescreen.signaling.SIGNALING_PATH
import com.mertbek.sharescreen.signaling.SignalCodec
import com.mertbek.sharescreen.signaling.SignalMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

fun lanSignalingUrl(host: String, port: Int): String = "ws://$host:$port$SIGNALING_PATH"

/**
 * How often the engine pings a connection, so one that went silent without closing, say behind a router
 * that forgot it, is noticed and replaced. Browsers answer pings but cannot send them, so the web leaves
 * it off and sends a ping message instead, see [defaultPingMessageMillis].
 */
internal expect val keepAliveMillis: Long

/** How often a connection sends a ping message, 0 where the engine pings it already. */
internal expect val defaultPingMessageMillis: Long

fun defaultHttpClient(pingIntervalMillis: Long = keepAliveMillis): HttpClient = HttpClient {
    install(WebSockets) {
        this.pingIntervalMillis = pingIntervalMillis
    }
}

class SignalingClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val pingMessageMillis: Long = defaultPingMessageMillis,
) {

    suspend fun connect(url: String, hello: SignalMessage.Hello): SignalingConnection {
        val connection = SignalingConnection(httpClient.webSocketSession(url), pingMessageMillis)
        connection.send(hello)
        return connection
    }
}

class SignalingConnection internal constructor(
    private val session: DefaultClientWebSocketSession,
    pingMessageMillis: Long = 0,
) {

    private val outbox = Channel<SignalMessage>(Channel.UNLIMITED)

    private val heard = MutableStateFlow(0)

    private val writer = session.launch {
        for (message in outbox) {
            session.send(Frame.Text(SignalCodec.encode(message)))
        }
    }

    init {
        if (pingMessageMillis > 0) session.launch { keepAlive(pingMessageMillis) }
    }

    val messages: Flow<SignalMessage> = flow {
        for (frame in session.incoming) {
            heard.update { it + 1 }
            if (frame !is Frame.Text) continue
            val message = try {
                SignalCodec.decode(frame.readText())
            } catch (_: SerializationException) {
                continue
            }
            if (message is SignalMessage.Pong) continue
            emit(message)
        }
    }

    private suspend fun keepAlive(interval: Long) {
        var seen = heard.value
        var silent = 0
        while (true) {
            delay(interval)
            if (heard.value != seen) {
                seen = heard.value
                silent = 0
            } else if (++silent >= SILENT_PINGS) {
                runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "No answer")) }
                return
            }
            send(SignalMessage.Ping)
        }
    }

    fun send(message: SignalMessage) {
        outbox.trySend(message)
    }

    suspend fun close() {
        outbox.close()
        session.close()
    }

    suspend fun leave() {
        send(SignalMessage.Leave)
        outbox.close()
        withTimeoutOrNull(LEAVE_FLUSH_TIMEOUT) {
            writer.join()
            session.flush()
        }
        session.close()
    }

    private companion object {
        val LEAVE_FLUSH_TIMEOUT = 1.seconds
        const val SILENT_PINGS = 4
    }
}
