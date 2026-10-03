package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.signaling.PeerRole
import com.mertbek.sharescreen.signaling.RoomManager
import com.mertbek.sharescreen.signaling.SignalMessage
import com.mertbek.sharescreen.signaling.SignalingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PingMessageTest {

    private fun client() = HttpClient(CIO) { install(WebSockets) }

    @Test
    fun `a connection that sends ping messages stays open and never shows the pongs`() = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") { signalingModule(RoomManager(SignalingConfig())) }
        server.startSuspend(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val connection = SignalingClient(client(), pingMessageMillis = 50)
            .connect("ws://127.0.0.1:$port/ws", SignalMessage.Hello(PeerRole.HOST, "Host"))
        val received = mutableListOf<SignalMessage>()
        val collecting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        var ended = false
        connection.messages.onEach { received += it }.launchIn(collecting).invokeOnCompletion { ended = true }
        try {
            kotlinx.coroutines.delay(1_000)

            assertTrue(!ended, "the connection should still be open")
            assertEquals(1, received.size)
            assertTrue(received.single() is SignalMessage.Welcome)
        } finally {
            collecting.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            connection.close()
            server.stop(100, 500)
        }
    }

    @Test
    fun `a connection that hears nothing back is closed`() = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") { for (frame in incoming) Unit }
            }
        }
        server.startSuspend(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val connection = SignalingClient(client(), pingMessageMillis = 50)
            .connect("ws://127.0.0.1:$port/ws", SignalMessage.Hello(PeerRole.HOST, "Host"))
        try {
            withTimeout(5.seconds) { connection.messages.collect { } }
        } finally {
            connection.close()
            server.stop(100, 500)
        }
    }
}
