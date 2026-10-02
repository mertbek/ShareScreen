package com.mertbek.sharescreen.signaling

import com.mertbek.sharescreen.signaling.SignalMessage.Answer
import com.mertbek.sharescreen.signaling.SignalMessage.Error
import com.mertbek.sharescreen.signaling.SignalMessage.Hello
import com.mertbek.sharescreen.signaling.SignalMessage.Ice
import com.mertbek.sharescreen.signaling.SignalMessage.JoinDecision
import com.mertbek.sharescreen.signaling.SignalMessage.JoinRequest
import com.mertbek.sharescreen.signaling.SignalMessage.Kick
import com.mertbek.sharescreen.signaling.SignalMessage.Leave
import com.mertbek.sharescreen.signaling.SignalMessage.Offer
import com.mertbek.sharescreen.signaling.SignalMessage.PeerLeft
import com.mertbek.sharescreen.signaling.SignalMessage.SessionEnded
import com.mertbek.sharescreen.signaling.SignalMessage.Welcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomManagerTest {

    private class FakeLink : PeerLink {
        val received = mutableListOf<SignalMessage>()
        var closed = false

        override fun send(message: SignalMessage) {
            received += message
        }

        override fun close() {
            closed = true
        }

        fun takeAll(): List<SignalMessage> = received.toList().also { received.clear() }
    }

    private fun messages(vararg messages: SignalMessage): List<SignalMessage> = messages.toList()

    private var nextId = 0
    private var now = 0L
    private fun lanManager(maxViewers: Int = 4, maxWaiting: Int = 8) = RoomManager(
        SignalingConfig(
            singleRoom = true,
            hostSecret = SECRET,
            maxViewersPerRoom = maxViewers,
            maxWaitingPerRoom = maxWaiting,
            resumeGraceMillis = GRACE,
        ),
        newPeerId = { "peer${++nextId}" },
        clockMillis = { now },
    )

    private fun hostHello(pin: String? = PIN, secret: String? = SECRET) =
        Hello(PeerRole.HOST, "Host phone", pin = pin, hostSecret = secret)

    private fun viewerHello(pin: String? = PIN, name: String = "Viewer phone") =
        Hello(PeerRole.VIEWER, name, pin = pin)

    @Test
    fun `host opens the single LAN room`() = runTest {
        val link = FakeLink()
        val host = assertNotNull(lanManager().join(link, hostHello()))

        assertEquals(messages(Welcome(host.id, RoomManager.SINGLE_ROOM_CODE, host.id, resumeToken = host.resumeToken)), link.received)
    }

    @Test
    fun `host without the secret is refused`() = runTest {
        val link = FakeLink()
        assertNull(lanManager().join(link, hostHello(secret = "wrong")))

        assertEquals(messages(Error(ErrorCode.UNAUTHORIZED)), link.received)
        assertTrue(link.closed)
    }

    @Test
    fun `second host in single room mode is refused`() = runTest {
        val manager = lanManager()
        manager.join(FakeLink(), hostHello())
        val second = FakeLink()

        assertNull(manager.join(second, hostHello()))
        assertEquals(messages(Error(ErrorCode.HOST_EXISTS)), second.received)
    }

    @Test
    fun `viewer before any host finds no room`() = runTest {
        val link = FakeLink()
        assertNull(lanManager().join(link, viewerHello()))

        assertEquals(messages(Error(ErrorCode.ROOM_NOT_FOUND)), link.received)
    }

    @Test
    fun `viewer with wrong pin is refused`() = runTest {
        val manager = lanManager()
        manager.join(FakeLink(), hostHello())
        val link = FakeLink()

        assertNull(manager.join(link, viewerHello(pin = "000000")))
        assertEquals(messages(Error(ErrorCode.INVALID_PIN)), link.received)
        assertTrue(link.closed)
    }

    @Test
    fun `viewer join is announced to the host`() = runTest {
        val manager = lanManager()
        val hostLink = FakeLink()
        val host = assertNotNull(manager.join(hostLink, hostHello()))
        hostLink.takeAll()
        val viewerLink = FakeLink()

        val viewer = assertNotNull(manager.join(viewerLink, viewerHello()))

        assertEquals(messages(Welcome(viewer.id, RoomManager.SINGLE_ROOM_CODE, host.id, resumeToken = viewer.resumeToken)), viewerLink.received)
        assertEquals(messages(JoinRequest(viewer.id, "Viewer phone")), hostLink.received)
    }

    @Test
    fun `offers are not relayed before the host approves`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.host, Offer(to = room.viewer.id, sdp = "v=0"))

        assertTrue(room.viewerLink.received.isEmpty())
        assertEquals(ErrorCode.PROTOCOL, (room.hostLink.received.single() as Error).code)
    }

    @Test
    fun `approved viewer negotiates with the host and sender ids cannot be spoofed`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.host, JoinDecision(room.viewer.id, accepted = true))
        assertEquals(messages(JoinDecision(room.viewer.id, true)), room.viewerLink.takeAll())

        manager.handle(room.host, Offer(to = room.viewer.id, sdp = "offer", from = "spoofed"))
        assertEquals(messages(Offer(to = room.viewer.id, sdp = "offer", from = room.host.id)), room.viewerLink.takeAll())

        manager.handle(room.viewer, Answer(to = room.host.id, sdp = "answer"))
        manager.handle(room.viewer, Ice(to = room.host.id, candidate = "c", sdpMid = "0", sdpMLineIndex = 0))
        assertEquals(
            messages(
                Answer(to = room.host.id, sdp = "answer", from = room.viewer.id),
                Ice(to = room.host.id, candidate = "c", sdpMid = "0", sdpMLineIndex = 0, from = room.viewer.id),
            ),
            room.hostLink.takeAll(),
        )
    }

    @Test
    fun `viewers cannot message each other`() = runTest {
        val (manager, room) = roomWithViewer()
        manager.handle(room.host, JoinDecision(room.viewer.id, accepted = true))
        val otherLink = FakeLink()
        val other = assertNotNull(manager.join(otherLink, viewerHello(name = "Other")))
        manager.handle(room.host, JoinDecision(other.id, accepted = true))
        otherLink.takeAll()
        room.viewerLink.takeAll()

        manager.handle(room.viewer, Offer(to = other.id, sdp = "x"))

        assertTrue(otherLink.received.isEmpty())
        assertEquals(ErrorCode.PROTOCOL, (room.viewerLink.received.single() as Error).code)
    }

    @Test
    fun `rejected viewer is told and disconnected`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.host, JoinDecision(room.viewer.id, accepted = false))
        manager.disconnected(room.viewer, room.viewerLink)

        assertEquals(messages(Error(ErrorCode.REJECTED)), room.viewerLink.received)
        assertTrue(room.viewerLink.closed)
        assertTrue(room.hostLink.received.isEmpty())
    }

    @Test
    fun `room is full when max viewers are watching`() = runTest {
        val manager = lanManager(maxViewers = 1)
        val host = assertNotNull(manager.join(FakeLink(), hostHello()))
        val viewer = assertNotNull(manager.join(FakeLink(), viewerHello()))
        manager.handle(host, JoinDecision(viewer.id, accepted = true))
        val link = FakeLink()

        assertNull(manager.join(link, viewerHello()))
        assertEquals(messages(Error(ErrorCode.ROOM_FULL)), link.received)
    }

    @Test
    fun `requests waiting for approval do not fill the room`() = runTest {
        val manager = lanManager(maxViewers = 1)
        val hostLink = FakeLink()
        val host = assertNotNull(manager.join(hostLink, hostHello()))
        val links = List(3) { FakeLink() }
        val viewers = links.mapIndexed { i, link -> assertNotNull(manager.join(link, viewerHello(name = "Viewer $i"))) }
        hostLink.takeAll()

        manager.handle(host, JoinDecision(viewers[1].id, accepted = true))

        assertEquals(messages(JoinDecision(viewers[1].id, true)), links[1].received.drop(1))
        for (i in listOf(0, 2)) {
            assertEquals(messages(Error(ErrorCode.ROOM_FULL)), links[i].received.drop(1))
            assertTrue(links[i].closed)
        }
        assertEquals(messages(PeerLeft(viewers[0].id), PeerLeft(viewers[2].id)), hostLink.received)
    }

    @Test
    fun `a place freed by a viewer who leaves takes a new request`() = runTest {
        val manager = lanManager(maxViewers = 1)
        val host = assertNotNull(manager.join(FakeLink(), hostHello()))
        val viewer = assertNotNull(manager.join(FakeLink(), viewerHello()))
        manager.handle(host, JoinDecision(viewer.id, accepted = true))

        manager.handle(viewer, Leave)

        assertNotNull(manager.join(FakeLink(), viewerHello()))
    }

    @Test
    fun `too many waiting requests are turned away`() = runTest {
        val manager = lanManager(maxWaiting = 2)
        manager.join(FakeLink(), hostHello())
        repeat(2) { assertNotNull(manager.join(FakeLink(), viewerHello())) }
        val link = FakeLink()

        assertNull(manager.join(link, viewerHello()))
        assertEquals(messages(Error(ErrorCode.ROOM_FULL)), link.received)
    }

    @Test
    fun `host is told when a viewer leaves`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.viewer, Leave)

        assertEquals(messages(PeerLeft(room.viewer.id)), room.hostLink.received)
    }

    @Test
    fun `viewers are disconnected when the host leaves`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.host, Leave)

        assertEquals(messages(SessionEnded), room.viewerLink.received)
        assertTrue(room.viewerLink.closed)
        assertNotNull(manager.join(FakeLink(), hostHello()))
    }

    @Test
    fun `host can kick a viewer`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.host, Kick(room.viewer.id))

        assertEquals(messages(Error(ErrorCode.KICKED)), room.viewerLink.received)
        assertTrue(room.viewerLink.closed)
        assertEquals(messages(PeerLeft(room.viewer.id)), room.hostLink.received)
    }

    @Test
    fun `viewers cannot send host-only messages`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.handle(room.viewer, JoinDecision(room.viewer.id, accepted = true))

        assertEquals(ErrorCode.PROTOCOL, (room.viewerLink.received.single() as Error).code)
        assertTrue(room.hostLink.received.isEmpty())
    }

    @Test
    fun `multi-room server generates codes and matches them case-insensitively`() = runTest {
        val manager = RoomManager(SignalingConfig(), newRoomCode = { "ABC234" })
        val hostLink = FakeLink()
        manager.join(hostLink, Hello(PeerRole.HOST, "Host"))
        assertEquals("ABC234", (hostLink.received.single() as Welcome).roomCode)

        val viewerLink = FakeLink()
        assertNotNull(manager.join(viewerLink, Hello(PeerRole.VIEWER, "Viewer", roomCode = "abc234")))
    }

    @Test
    fun `hosts and viewers receive fresh ice servers when they join`() = runTest {
        var issued = 0
        val manager = RoomManager(
            SignalingConfig(iceServers = { listOf(IceServerConfig(listOf("turn:turn.test"), "user${++issued}", "secret")) }),
            newRoomCode = { "ROOM22" },
        )
        val hostLink = FakeLink()
        manager.join(hostLink, Hello(PeerRole.HOST, "Host"))
        val viewerLink = FakeLink()
        manager.join(viewerLink, Hello(PeerRole.VIEWER, "Viewer", roomCode = "ROOM22"))

        assertEquals("user1", (hostLink.received.first() as Welcome).iceServers.single().username)
        assertEquals("user2", (viewerLink.received.first() as Welcome).iceServers.single().username)
    }

    private fun lockingManager(clock: () -> Long) = RoomManager(
        SignalingConfig(singleRoom = true, hostSecret = SECRET, maxWrongPinsPerMinute = 3),
        clockMillis = clock,
    )

    private suspend fun RoomManager.guessWrong(times: Int, address: String?) = repeat(times) { attempt ->
        val link = FakeLink()
        join(link, viewerHello(pin = "00000$attempt"), address)
        assertEquals(messages(Error(ErrorCode.INVALID_PIN)), link.received)
    }

    @Test
    fun `guessing the pin locks the guesser out for a minute`() = runTest {
        var now = 0L
        val manager = lockingManager { now }
        manager.join(FakeLink(), hostHello())
        manager.guessWrong(3, address = "10.0.0.66")

        now = 30_000
        val locked = FakeLink()
        assertNull(manager.join(locked, viewerHello(), "10.0.0.66"), "Even the right PIN is refused while locked")
        assertEquals(messages(Error(ErrorCode.TOO_MANY_ATTEMPTS)), locked.received)

        now = 61_000
        assertNotNull(manager.join(FakeLink(), viewerHello(), "10.0.0.66"))
    }

    @Test
    fun `someone guessing the pin does not keep the others out`() = runTest {
        val manager = lockingManager { 0L }
        manager.join(FakeLink(), hostHello())
        manager.guessWrong(3, address = "10.0.0.66")

        assertNotNull(manager.join(FakeLink(), viewerHello(), "10.0.0.7"))
    }

    @Test
    fun `a locked room gives each new address a single guess`() = runTest {
        val manager = lockingManager { 0L }
        manager.join(FakeLink(), hostHello())
        manager.guessWrong(3, address = "10.0.0.66")
        manager.guessWrong(1, address = "10.0.0.67")

        val again = FakeLink()
        assertNull(manager.join(again, viewerHello(pin = "999999"), "10.0.0.67"))
        assertEquals(messages(Error(ErrorCode.TOO_MANY_ATTEMPTS)), again.received)
    }

    @Test
    fun `guesses from many addresses lock the room for everyone`() = runTest {
        val manager = lockingManager { 0L }
        manager.join(FakeLink(), hostHello())
        repeat(256) { manager.guessWrong(1, address = "10.0.${it / 250}.${it % 250}") }

        val locked = FakeLink()
        assertNull(manager.join(locked, viewerHello(), "10.0.9.1"))
        assertEquals(messages(Error(ErrorCode.TOO_MANY_ATTEMPTS)), locked.received)
    }

    @Test
    fun `connections without an address share one lock`() = runTest {
        val manager = lockingManager { 0L }
        manager.join(FakeLink(), hostHello())
        manager.guessWrong(3, address = null)

        val locked = FakeLink()
        assertNull(manager.join(locked, viewerHello()))
        assertEquals(messages(Error(ErrorCode.TOO_MANY_ATTEMPTS)), locked.received)
    }

    @Test
    fun `unsupported protocol version is refused`() = runTest {
        val link = FakeLink()
        assertNull(lanManager().join(link, hostHello().copy(protocolVersion = PROTOCOL_VERSION + 1)))

        assertEquals(messages(Error(ErrorCode.UNSUPPORTED_VERSION)), link.received)
    }

    @Test
    fun `a dropped viewer keeps its place and resumes without approval`() = runTest {
        val (manager, room) = roomWithViewer()
        manager.handle(room.host, JoinDecision(room.viewer.id, accepted = true))
        room.viewerLink.takeAll()

        manager.disconnected(room.viewer, room.viewerLink)
        manager.handle(room.host, Offer(to = room.viewer.id, sdp = "restart"))
        assertTrue(room.hostLink.received.isEmpty(), "The host is not told about a drop")

        now = GRACE - 1
        val newLink = FakeLink()
        val resumed = manager.join(newLink, Hello(PeerRole.VIEWER, "Viewer phone", resumeToken = room.viewer.resumeToken))
        assertSame(room.viewer, resumed)
        assertEquals(
            messages(
                Welcome(room.viewer.id, RoomManager.SINGLE_ROOM_CODE, room.host.id, resumeToken = room.viewer.resumeToken, resumed = true),
                Offer(to = room.viewer.id, sdp = "restart", from = room.host.id),
            ),
            newLink.received,
        )
    }

    @Test
    fun `a dropped host keeps its room and its viewers`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.disconnected(room.host, room.hostLink)
        manager.handle(room.viewer, Leave)
        assertTrue(room.viewerLink.received.isEmpty())

        val newLink = FakeLink()
        assertSame(room.host, manager.join(newLink, Hello(PeerRole.HOST, "Host phone", resumeToken = room.host.resumeToken)))
        assertEquals(
            messages(
                Welcome(room.host.id, RoomManager.SINGLE_ROOM_CODE, room.host.id, resumeToken = room.host.resumeToken, resumed = true),
                PeerLeft(room.viewer.id),
            ),
            newLink.received,
        )
    }

    @Test
    fun `a viewer that does not resume in time is gone`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.disconnected(room.viewer, room.viewerLink)
        now = GRACE
        manager.expire()

        assertEquals(messages(PeerLeft(room.viewer.id)), room.hostLink.received)
        val late = FakeLink()
        assertNull(manager.join(late, Hello(PeerRole.VIEWER, "Viewer phone", resumeToken = room.viewer.resumeToken)))
        assertEquals(messages(Error(ErrorCode.RESUME_FAILED)), late.received)
    }

    @Test
    fun `a host that does not resume in time ends the session`() = runTest {
        val (manager, room) = roomWithViewer()

        manager.disconnected(room.host, room.hostLink)
        now = GRACE
        manager.expire()

        assertEquals(messages(SessionEnded), room.viewerLink.received)
        assertTrue(room.viewerLink.closed)
    }

    @Test
    fun `resuming replaces a connection that has not noticed it is gone`() = runTest {
        val (manager, room) = roomWithViewer()
        val newLink = FakeLink()

        manager.join(newLink, Hello(PeerRole.VIEWER, "Viewer phone", resumeToken = room.viewer.resumeToken))
        assertTrue(room.viewerLink.closed)
        manager.disconnected(room.viewer, room.viewerLink)
        newLink.takeAll()
        manager.handle(room.host, JoinDecision(room.viewer.id, accepted = true))

        assertEquals(messages(JoinDecision(room.viewer.id, true)), newLink.received)
    }

    @Test
    fun `a resume token only works for its own role`() = runTest {
        val (manager, room) = roomWithViewer()
        val link = FakeLink()

        assertNull(manager.join(link, Hello(PeerRole.HOST, "Host phone", resumeToken = room.viewer.resumeToken)))
        assertEquals(messages(Error(ErrorCode.RESUME_FAILED)), link.received)
    }

    @Test
    fun `peers get distinct resume tokens`() = runTest {
        val (_, room) = roomWithViewer()

        assertNotEquals(room.host.resumeToken, room.viewer.resumeToken)
        assertTrue(room.viewer.resumeToken.length >= 22, "128 random bits")
    }

    private class RoomFixture(val host: Member, val hostLink: FakeLink, val viewer: Member, val viewerLink: FakeLink)

    private suspend fun roomWithViewer(): Pair<RoomManager, RoomFixture> {
        val manager = lanManager()
        val hostLink = FakeLink()
        val host = assertNotNull(manager.join(hostLink, hostHello()))
        val viewerLink = FakeLink()
        val viewer = assertNotNull(manager.join(viewerLink, viewerHello()))
        hostLink.takeAll()
        viewerLink.takeAll()
        return manager to RoomFixture(host, hostLink, viewer, viewerLink)
    }

    private companion object {
        const val SECRET = "host-secret"
        const val PIN = "482913"
        const val GRACE = 30_000L
    }
}
