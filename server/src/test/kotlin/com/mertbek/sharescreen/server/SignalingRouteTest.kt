package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.signaling.ErrorCode
import com.mertbek.sharescreen.signaling.PeerRole
import com.mertbek.sharescreen.signaling.RoomManager
import com.mertbek.sharescreen.signaling.SIGNALING_PATH
import com.mertbek.sharescreen.signaling.SignalCodec
import com.mertbek.sharescreen.signaling.SignalMessage
import com.mertbek.sharescreen.signaling.SignalingConfig
import com.mertbek.sharescreen.signaling.SignalMessage.Error
import com.mertbek.sharescreen.signaling.SignalMessage.Hello
import com.mertbek.sharescreen.signaling.SignalMessage.JoinDecision
import com.mertbek.sharescreen.signaling.SignalMessage.JoinRequest
import com.mertbek.sharescreen.signaling.SignalMessage.Leave
import com.mertbek.sharescreen.signaling.SignalMessage.Offer
import com.mertbek.sharescreen.signaling.SignalMessage.PeerLeft
import com.mertbek.sharescreen.signaling.SignalMessage.SessionEnded
import com.mertbek.sharescreen.signaling.SignalMessage.Welcome
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class SignalingRouteTest {

    @Test
    fun `host and viewer negotiate over websockets`() = signalingTest {
        val host = connect()
        host.sendMessage(Hello(PeerRole.HOST, "Host", pin = PIN, hostSecret = SECRET))
        val hostWelcome = host.receiveMessage() as Welcome

        val viewer = connect()
        viewer.sendMessage(Hello(PeerRole.VIEWER, "Viewer", pin = PIN))
        val viewerWelcome = viewer.receiveMessage() as Welcome
        assertEquals(hostWelcome.peerId, viewerWelcome.hostId)
        assertEquals(JoinRequest(viewerWelcome.peerId, "Viewer"), host.receiveMessage())

        host.sendMessage(JoinDecision(viewerWelcome.peerId, accepted = true))
        assertEquals(JoinDecision(viewerWelcome.peerId, true), viewer.receiveMessage())

        host.sendMessage(Offer(to = viewerWelcome.peerId, sdp = "v=0"))
        assertEquals(Offer(to = viewerWelcome.peerId, sdp = "v=0", from = hostWelcome.peerId), viewer.receiveMessage())

        viewer.sendMessage(Leave)
        viewer.close()
        assertEquals(PeerLeft(viewerWelcome.peerId), host.receiveMessage())
    }

    @Test
    fun `a viewer whose connection drops resumes on a new one`() = signalingTest {
        val host = connect()
        host.sendMessage(Hello(PeerRole.HOST, "Host", pin = PIN, hostSecret = SECRET))
        host.receiveMessage()
        val viewer = connect()
        viewer.sendMessage(Hello(PeerRole.VIEWER, "Viewer", pin = PIN))
        val welcome = viewer.receiveMessage() as Welcome
        host.receiveMessage()

        viewer.close()
        val again = connect()
        again.sendMessage(Hello(PeerRole.VIEWER, "Viewer", resumeToken = welcome.resumeToken))

        assertEquals(welcome.copy(resumed = true), again.receiveMessage())
        host.sendMessage(JoinDecision(welcome.peerId, accepted = true))
        assertEquals(JoinDecision(welcome.peerId, true), again.receiveMessage())
    }

    @Test
    fun `wrong pin gets an error and the socket is closed`() = signalingTest {
        val host = connect()
        host.sendMessage(Hello(PeerRole.HOST, "Host", pin = PIN, hostSecret = SECRET))
        host.receiveMessage()

        val viewer = connect()
        viewer.sendMessage(Hello(PeerRole.VIEWER, "Viewer", pin = "000000"))
        assertEquals(Error(ErrorCode.INVALID_PIN), viewer.receiveMessage())
        assertNull(viewer.incoming.receiveCatching().getOrNull())
    }

    @Test
    fun `first message must be hello`() = signalingTest {
        val client = connect()
        client.sendMessage(Offer(to = "someone", sdp = "x"))
        assertEquals(ErrorCode.PROTOCOL, (client.receiveMessage() as Error).code)
    }

    @Test
    fun `viewers are told when the host leaves`() = signalingTest {
        val host = connect()
        host.sendMessage(Hello(PeerRole.HOST, "Host", pin = PIN, hostSecret = SECRET))
        host.receiveMessage()
        val viewer = connect()
        viewer.sendMessage(Hello(PeerRole.VIEWER, "Viewer", pin = PIN))
        viewer.receiveMessage()

        host.sendMessage(Leave)
        host.close()

        assertEquals(SessionEnded, viewer.receiveMessage())
    }

    @Test
    fun `browsers are turned away when the server refuses their origin`() = signalingTest(allowBrowserOrigins = false) {
        val host = connect()
        host.sendMessage(Hello(PeerRole.HOST, "Host", pin = PIN, hostSecret = SECRET))
        host.receiveMessage()

        repeat(12) {
            val browser = createClient { install(WebSockets) }
                .webSocketSession(SIGNALING_PATH) { header(HttpHeaders.Origin, "https://evil.example") }
            runCatching { browser.sendMessage(Hello(PeerRole.VIEWER, "Page", pin = "000000")) }
            assertNull(browser.incoming.receiveCatching().getOrNull())
        }

        val viewer = connect()
        viewer.sendMessage(Hello(PeerRole.VIEWER, "Viewer", pin = PIN))
        assertIs<Welcome>(viewer.receiveMessage())
    }

    private fun signalingTest(
        allowBrowserOrigins: Boolean = true,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            signalingModule(RoomManager(SignalingConfig(singleRoom = true, hostSecret = SECRET)), allowBrowserOrigins)
        }
        withTimeout(10.seconds) { block() }
    }

    private suspend fun ApplicationTestBuilder.connect(): WebSocketSession =
        createClient { install(WebSockets) }.webSocketSession(SIGNALING_PATH)

    private suspend fun WebSocketSession.sendMessage(message: SignalMessage) =
        send(Frame.Text(SignalCodec.encode(message)))

    private suspend fun WebSocketSession.receiveMessage(): SignalMessage =
        SignalCodec.decode((incoming.receive() as Frame.Text).readText())

    private companion object {
        const val SECRET = "host-secret"
        const val PIN = "482913"
    }
}
