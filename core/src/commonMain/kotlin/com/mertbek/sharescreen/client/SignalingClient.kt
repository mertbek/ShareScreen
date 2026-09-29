package com.mertbek.sharescreen.client

import com.mertbek.sharescreen.signaling.SIGNALING_PATH
import com.mertbek.sharescreen.signaling.SignalCodec
import com.mertbek.sharescreen.signaling.SignalMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

fun lanSignalingUrl(host: String, port: Int): String = "ws://$host:$port$SIGNALING_PATH"

fun defaultHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
}

class SignalingClient(private val httpClient: HttpClient = defaultHttpClient()) {

    suspend fun connect(url: String, hello: SignalMessage.Hello): SignalingConnection {
        val connection = SignalingConnection(httpClient.webSocketSession(url))
        connection.send(hello)
        return connection
    }
}

class SignalingConnection internal constructor(private val session: DefaultClientWebSocketSession) {

    private val outbox = Channel<SignalMessage>(Channel.UNLIMITED)

    private val writer = session.launch {
        for (message in outbox) {
            session.send(Frame.Text(SignalCodec.encode(message)))
        }
    }

    val messages: Flow<SignalMessage> = flow {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val message = try {
                SignalCodec.decode(frame.readText())
            } catch (_: SerializationException) {
                continue
            }
            emit(message)
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
        withTimeoutOrNull(LEAVE_FLUSH_TIMEOUT) { writer.join() }
        session.close()
    }

    private companion object {
        val LEAVE_FLUSH_TIMEOUT = 1.seconds
    }
}
