package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.PointerAction
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.platform.InputInjector
import com.mertbek.sharescreen.platform.LanServer
import com.mertbek.sharescreen.session.EndReason
import com.mertbek.sharescreen.session.HostSession
import com.mertbek.sharescreen.session.HostState
import com.mertbek.sharescreen.session.InternetRoom
import com.mertbek.sharescreen.session.JoinTarget
import com.mertbek.sharescreen.session.ViewerSession
import com.mertbek.sharescreen.session.ViewerState
import com.mertbek.sharescreen.settings.RememberedDevices
import com.mertbek.sharescreen.signaling.RoomManager
import com.mertbek.sharescreen.signaling.SignalingConfig
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun cioClient() = HttpClient(CIO) { install(WebSockets) }

class EmbeddedLanServer : LanServer {
    private val server = EmbeddedSignalingServer()

    override suspend fun start(hostSecret: String) = server.start(hostSecret)

    override suspend fun stop() = server.stop()
}

class RecordingInjector : InputInjector {
    override val platform = HostPlatform.DESKTOP
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun touch(time: Long, pointers: List<TouchPointer>) {
        events += "touch:${pointers.size}"
    }

    override fun navigate(action: NavAction) {
        events += "navigate:$action"
    }

    override fun type(deleteBefore: Int, text: String) {
        events += "type:$deleteBefore:$text"
    }

    override fun press(key: ControlKey) {
        events += "press:$key"
    }

    override fun pointer(message: ControlMessage.Pointer) {
        events += "pointer:${message.action}"
    }

    override fun keyboard(message: ControlMessage.Keyboard) {
        events += "keyboard:${message.key}"
    }

    override fun releaseInput() {
        events += "release"
    }
}

class SessionTest {

    private suspend fun <T> eventually(block: suspend () -> T?): T = withTimeout(10.seconds) {
        var result = block()
        while (result == null) {
            kotlinx.coroutines.delay(20)
            result = block()
        }
        result
    }

    private class Setup(allowControl: Boolean) {
        val engine = FakeRtcEngine()
        val injector = RecordingInjector()
        val host = HostSession(
            rtc = engine,
            signalingClient = SignalingClient(cioClient()),
            deviceName = DeviceName("Host"),
            lanServer = EmbeddedLanServer(),
            inputInjector = injector,
        )
        val viewer = ViewerSession(engine, SignalingClient(cioClient()), DeviceName("Viewer"))
        val allow = allowControl

        fun start(lanPin: Boolean = false) = host.start(FakeMedia(hasAudio = true), internetServer = null, allowControl = allow, lanPin = lanPin)
    }

    private suspend fun Setup.connectViewer(): HostState.Live {
        start()
        val live = eventually { host.state.value as? HostState.Live }
        viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), live.pin)
        val pending = eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }
        host.approve(pending.id)
        eventually { viewer.state.value as? ViewerState.Watching }
        return live
    }

    @Test
    fun `viewer is approved and receives the shared screen`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.connectViewer()

        val live = eventually { (setup.host.state.value as? HostState.Live)?.takeIf { it.viewers.firstOrNull()?.isConnected == true } }
        assertEquals("Viewer", live.viewers.single().deviceName)
        assertTrue(setup.viewer.hasAudio.first { it })
        assertEquals(1280, eventually { setup.viewer.streamInfo.value }.width)

        setup.viewer.close()
        setup.host.stop().join()
    }

    @Test
    fun `a nearby viewer asks to watch without a pin`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.start()
        val live = eventually { setup.host.state.value as? HostState.Live }
        assertFalse(live.lanPin)

        setup.viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), pin = null)
        setup.host.approve(eventually { (setup.host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id)
        eventually { setup.viewer.state.value as? ViewerState.Watching }

        setup.viewer.close()
        setup.host.stop().join()
    }

    @Test
    fun `a host that asks nearby viewers for the pin turns away a wrong or missing one`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.start(lanPin = true)
        val live = eventually { setup.host.state.value as? HostState.Live }
        assertTrue(live.lanPin)

        setup.viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), "000000".takeIf { it != live.pin } ?: "111111")
        assertEquals(EndReason.INVALID_PIN, eventually { setup.viewer.state.value as? ViewerState.Ended }.reason)
        val withoutPin = ViewerSession(setup.engine, SignalingClient(cioClient()), DeviceName("Viewer"))
        withoutPin.connect(JoinTarget.Lan("127.0.0.1", live.port), pin = null)
        assertEquals(EndReason.INVALID_PIN, eventually { withoutPin.state.value as? ViewerState.Ended }.reason)

        setup.host.stop().join()
    }

    private class Remembering {
        val hostDevices = RememberedDevices(MapSettings())
        val viewerDevices = RememberedDevices(MapSettings())
        val engine = FakeRtcEngine()
        val injector = RecordingInjector()
        val host = HostSession(
            rtc = engine,
            signalingClient = SignalingClient(cioClient()),
            deviceName = DeviceName("Host"),
            lanServer = EmbeddedLanServer(),
            inputInjector = injector,
            rememberedDevices = hostDevices,
        )
        private val client = SignalingClient(cioClient())

        fun viewer(name: String = "Tablet", devices: RememberedDevices = viewerDevices) =
            ViewerSession(engine, client, DeviceName(name), devices)
    }

    /** Shares with remote control allowed and returns how a viewer reaches this host. */
    private suspend fun Remembering.shareWithControl(): JoinTarget.Lan {
        host.start(FakeMedia(hasAudio = true), internetServer = null, allowControl = true)
        return JoinTarget.Lan("127.0.0.1", eventually { host.state.value as? HostState.Live }.port, hostDevices.hostId)
    }

    private suspend fun Remembering.watch(viewer: ViewerSession, target: JoinTarget.Lan, approve: Boolean) {
        viewer.connect(target, pin = null)
        if (approve) host.approve(eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id)
        eventually { viewer.state.value as? ViewerState.Watching }
        eventually { viewer.control.value.takeIf { it.available } }
    }

    private suspend fun Remembering.controlOf(name: String): ControlRole =
        eventually { (host.state.value as? HostState.Live)?.viewers?.find { it.deviceName == name }?.control?.takeIf { it != ControlRole.NONE } }

    /** Shares, lets a viewer in with "remember this device" ticked and sends it away again. */
    private suspend fun Remembering.rememberViewer(lanPin: Boolean): JoinTarget.Lan {
        host.start(FakeMedia(hasAudio = true), internetServer = null, allowControl = false, lanPin = lanPin)
        val live = eventually { host.state.value as? HostState.Live }
        val target = JoinTarget.Lan("127.0.0.1", live.port, hostDevices.hostId)
        val first = viewer()
        first.connect(target, live.pin)
        host.approve(eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id, remember = true)
        eventually { first.state.value as? ViewerState.Watching }
        eventually { viewerDevices.knowsHost(hostDevices.hostId).takeIf { it } }
        first.close()
        eventually { (host.state.value as? HostState.Live)?.viewers?.isEmpty()?.takeIf { it } }
        return target
    }

    @Test
    fun `a remembered device comes back without the pin or an approval`() = runBlocking {
        val setup = Remembering()
        val target = setup.rememberViewer(lanPin = true)
        assertEquals(listOf("Tablet"), setup.hostDevices.viewers.value.map { it.name })

        val again = setup.viewer()
        again.connect(target, pin = null)
        eventually { again.state.value as? ViewerState.Watching }
        val live = setup.host.state.value as HostState.Live
        assertTrue(live.viewers.single().remembered)
        assertTrue(live.pendingViewers.isEmpty())

        again.close()
        setup.host.stop().join()
    }

    @Test
    fun `a device the host forgot asks the usual way again`() = runBlocking {
        val setup = Remembering()
        val target = setup.rememberViewer(lanPin = false)
        setup.hostDevices.forgetViewer(setup.hostDevices.viewers.value.single().key)

        val refused = setup.viewer()
        refused.connect(target, pin = null)
        assertEquals(EndReason.PASS_REFUSED, eventually { refused.state.value as? ViewerState.Ended }.reason)
        assertFalse(setup.viewerDevices.knowsHost(setup.hostDevices.hostId))

        val asking = setup.viewer()
        asking.connect(target, pin = null)
        setup.host.approve(eventually { (setup.host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id)
        eventually { asking.state.value as? ViewerState.Watching }
        assertFalse((setup.host.state.value as HostState.Live).viewers.single().remembered)

        asking.close()
        setup.host.stop().join()
    }

    @Test
    fun `a device let in with control without asking comes back and takes control on its own`() = runBlocking {
        val setup = Remembering()
        val target = setup.shareWithControl()
        val first = setup.viewer()
        setup.watch(first, target, approve = true)
        first.requestControl()
        setup.controlOf("Tablet")
        setup.host.grantControl((setup.host.state.value as HostState.Live).viewers.single().id, remember = true)
        eventually { first.control.value.takeIf { it.role == ControlRole.GRANTED } }
        eventually { setup.viewerDevices.knowsHost(setup.hostDevices.hostId).takeIf { it } }
        assertTrue(setup.hostDevices.viewers.value.single().control)
        assertTrue((setup.host.state.value as HostState.Live).viewers.single().remembered)
        first.close()
        eventually { (setup.host.state.value as? HostState.Live)?.viewers?.isEmpty()?.takeIf { it } }

        val again = setup.viewer()
        setup.watch(again, target, approve = false)
        again.requestControl()
        eventually { again.control.value.takeIf { it.role == ControlRole.GRANTED } }
        again.navigate(NavAction.HOME)
        eventually { setup.injector.events.takeIf { "navigate:HOME" in it } }

        again.close()
        setup.host.stop().join()
    }

    @Test
    fun `a device trusted with control still asks while someone else has it, or once the host takes the trust back`() = runBlocking {
        val setup = Remembering()
        val invitation = setup.hostDevices.rememberViewer("Tablet", control = true)
        setup.viewerDevices.rememberHost(invitation)
        val target = setup.shareWithControl()

        val phone = setup.viewer("Phone", RememberedDevices(MapSettings()))
        setup.watch(phone, target, approve = true)
        phone.requestControl()
        setup.controlOf("Phone")
        setup.host.grantControl((setup.host.state.value as HostState.Live).viewers.single().id)
        eventually { phone.control.value.takeIf { it.role == ControlRole.GRANTED } }

        val tablet = setup.viewer()
        setup.watch(tablet, target, approve = false)
        tablet.requestControl()
        assertEquals(ControlRole.REQUESTED, setup.controlOf("Tablet"))
        assertEquals(ControlRole.GRANTED, setup.controlOf("Phone"))
        tablet.close()
        phone.close()
        eventually { (setup.host.state.value as? HostState.Live)?.viewers?.isEmpty()?.takeIf { it } }

        setup.hostDevices.allowControl(invitation.key, false)
        val back = setup.viewer()
        setup.watch(back, target, approve = false)
        back.requestControl()
        assertEquals(ControlRole.REQUESTED, setup.controlOf("Tablet"))

        back.close()
        setup.host.stop().join()
    }

    @Test
    fun `rejected viewer is told`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.start()
        val live = eventually { setup.host.state.value as? HostState.Live }
        setup.viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), live.pin)

        val pending = eventually { (setup.host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }
        setup.host.reject(pending.id)

        assertEquals(EndReason.REJECTED, eventually { setup.viewer.state.value as? ViewerState.Ended }.reason)
        setup.host.stop().join()
    }

    @Test
    fun `kicked viewer is disconnected`() = runBlocking {
        val setup = Setup(allowControl = false)
        val live = setup.connectViewer()
        assertIs<HostState.Live>(live)

        val id = eventually { (setup.host.state.value as? HostState.Live)?.viewers?.firstOrNull() }.id
        setup.host.kick(id)

        assertEquals(EndReason.KICKED, eventually { setup.viewer.state.value as? ViewerState.Ended }.reason)
        setup.host.stop().join()
    }

    @Test
    fun `granted viewer controls the host and stops when revoked`() = runBlocking {
        val setup = Setup(allowControl = true)
        setup.connectViewer()
        eventually { setup.viewer.control.value.takeIf { it.available } }

        setup.viewer.requestControl()
        val requested = eventually {
            (setup.host.state.value as? HostState.Live)?.viewers?.firstOrNull { it.control == ControlRole.REQUESTED }
        }
        setup.host.grantControl(requested.id)
        eventually { setup.viewer.control.value.takeIf { it.role == ControlRole.GRANTED } }
        assertEquals(HostPlatform.DESKTOP, setup.viewer.control.value.platform)

        setup.viewer.touch(1, listOf(TouchPointer(1, 0.5f, 0.5f)))
        setup.viewer.navigate(NavAction.HOME)
        setup.viewer.pointer(ControlMessage.Pointer(PointerAction.DOWN, 0.1f, 0.2f))
        eventually { setup.injector.events.takeIf { it.containsAll(listOf("touch:1", "navigate:HOME", "pointer:DOWN")) } }

        setup.host.revokeControl()
        eventually { setup.viewer.control.value.takeIf { it.role == ControlRole.NONE } }
        setup.viewer.touch(2, listOf(TouchPointer(1, 0.9f, 0.9f)))
        kotlinx.coroutines.delay(200)
        assertEquals(1, setup.injector.events.count { it.startsWith("touch") })
        assertTrue("release" in setup.injector.events)

        setup.viewer.close()
        setup.host.stop().join()
    }

    @Test
    fun `a viewer who closes the session leaves the host's list right away`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.start()
        val live = eventually { setup.host.state.value as? HostState.Live }
        val signaling = SignalingClient(cioClient())
        repeat(50) {
            val viewer = ViewerSession(setup.engine, signaling, DeviceName("Viewer $it"))
            viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), live.pin)
            setup.host.approve(eventually { (setup.host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id)
            eventually { viewer.state.value as? ViewerState.Watching }
            viewer.close()
            withTimeout(3.seconds) {
                while ((setup.host.state.value as HostState.Live).viewers.isNotEmpty()) kotlinx.coroutines.delay(20)
            }
        }
        setup.host.stop().join()
    }

    @Test
    fun `an internet-only host stays live when the server cannot be reached`() = runBlocking {
        val host = HostSession(FakeRtcEngine(), SignalingClient(cioClient()), DeviceName("Web"))
        host.start(FakeMedia(), internetServer = "ws://127.0.0.1:9", allowControl = false)
        eventually { (host.state.value as? HostState.Live)?.internetRoom as? InternetRoom.Failed }
        kotlinx.coroutines.delay(500)
        assertIs<HostState.Live>(host.state.value)
        host.stop().join()
    }

    @Test
    fun `an internet-only host keeps its viewers when the server loses the room`() = runBlocking {
        fun internetServer(port: Int) = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            signalingModule(RoomManager(SignalingConfig()))
        }
        var server = internetServer(0).also { it.startSuspend(wait = false) }
        val port = server.engine.resolvedConnectors().first().port
        val address = "ws://127.0.0.1:$port"
        val engine = FakeRtcEngine()
        val host = HostSession(engine, SignalingClient(cioClient()), DeviceName("Web"))
        val viewer = ViewerSession(engine, SignalingClient(cioClient()), DeviceName("Viewer"))
        host.start(FakeMedia(), internetServer = address, allowControl = false)
        val room = eventually { (host.state.value as? HostState.Live)?.internetRoom as? InternetRoom.Open }
        viewer.connect(JoinTarget.Internet(address, room.roomCode), (host.state.value as HostState.Live).pin)
        host.approve(eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }.id)
        eventually { viewer.state.value as? ViewerState.Watching }

        server.stop(100, 500)
        server = internetServer(port).also { it.startSuspend(wait = false) }

        val live = withTimeout(40.seconds) {
            var state = host.state.value
            while (state is HostState.Live && state.internetRoom !is InternetRoom.Closed) {
                kotlinx.coroutines.delay(50)
                state = host.state.value
            }
            state
        }
        assertIs<HostState.Live>(live)
        assertEquals(listOf("Viewer"), live.viewers.map { it.deviceName })
        viewer.close()
        host.stop().join()
        server.stop(100, 500)
    }

    @Test
    fun `an approval given while the host reconnects still lets the viewer in`() = runBlocking {
        approvalAcrossDrop { relay -> relay.refusing = true; relay.cutAll() }
    }

    @Test
    fun `an approval lost on a dead connection is sent again after the reconnect`() = runBlocking {
        approvalAcrossDrop { relay -> relay.swallowing = true }
    }

    private suspend fun approvalAcrossDrop(drop: (Relay) -> Unit) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") { signalingModule(RoomManager(SignalingConfig())) }
        server.startSuspend(wait = false)
        val relay = Relay(server.engine.resolvedConnectors().first().port)
        val address = "ws://127.0.0.1:${relay.port}"
        val engine = FakeRtcEngine()
        val host = HostSession(engine, SignalingClient(cioClient()), DeviceName("Host"))
        val viewer = ViewerSession(engine, SignalingClient(cioClient()), DeviceName("Viewer"))
        try {
            host.start(FakeMedia(), internetServer = address, allowControl = false)
            val room = eventually { (host.state.value as? HostState.Live)?.internetRoom as? InternetRoom.Open }
            viewer.connect(JoinTarget.Internet(address, room.roomCode), (host.state.value as HostState.Live).pin)
            val pending = eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }

            drop(relay)
            if (relay.refusing) eventually { ((host.state.value as HostState.Live).internetRoom as? InternetRoom.Open)?.takeIf { it.reconnecting } }
            host.approve(pending.id)
            kotlinx.coroutines.delay(1_000)
            relay.cutAll()
            relay.refusing = false
            relay.swallowing = false

            withTimeout(30.seconds) {
                while (viewer.state.value !is ViewerState.Watching) kotlinx.coroutines.delay(20)
            }
        } finally {
            viewer.close()
            host.stop().join()
            relay.close()
            server.stop(100, 500)
        }
    }

    /** Passes TCP through to the server until the test cuts, refuses or swallows the traffic. */
    private class Relay(private val target: Int) {
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        @Volatile var refusing = false
        @Volatile var swallowing = false
        val port: Int get() = server.localPort

        init {
            kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (refusing) {
                        client.close()
                        continue
                    }
                    val upstream = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), target)
                    sockets += client
                    sockets += upstream
                    pipe(client, upstream)
                    pipe(upstream, client)
                }
            }
        }

        private fun pipe(from: java.net.Socket, to: java.net.Socket) = kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = from.getInputStream().read(buffer)
                    if (read < 0) break
                    if (!swallowing) to.getOutputStream().write(buffer, 0, read)
                }
            }
            runCatching { from.close() }
            runCatching { to.close() }
        }

        fun cutAll() {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }

        fun close() {
            cutAll()
            server.close()
        }
    }
}
