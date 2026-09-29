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
import com.mertbek.sharescreen.session.JoinTarget
import com.mertbek.sharescreen.session.ViewerSession
import com.mertbek.sharescreen.session.ViewerState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
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

        fun start() = host.start(FakeMedia(hasAudio = true), internetServer = null, allowControl = allow)
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
    fun `wrong pin ends the viewer session`() = runBlocking {
        val setup = Setup(allowControl = false)
        setup.start()
        val live = eventually { setup.host.state.value as? HostState.Live }

        setup.viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), "000000".takeIf { it != live.pin } ?: "111111")
        val ended = eventually { setup.viewer.state.value as? ViewerState.Ended }
        assertEquals(EndReason.INVALID_PIN, ended.reason)

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
}
