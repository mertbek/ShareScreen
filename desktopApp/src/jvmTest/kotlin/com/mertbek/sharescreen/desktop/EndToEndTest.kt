package com.mertbek.sharescreen.desktop

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.PointerAction
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.lan.EmbeddedLanServer
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.platform.InputInjector
import com.mertbek.sharescreen.rtc.DesktopCapturedMedia
import com.mertbek.sharescreen.rtc.DesktopRemoteVideo
import com.mertbek.sharescreen.rtc.DesktopRtcEngine
import com.mertbek.sharescreen.session.HostSession
import com.mertbek.sharescreen.session.HostState
import com.mertbek.sharescreen.session.JoinTarget
import com.mertbek.sharescreen.session.ViewerSession
import com.mertbek.sharescreen.session.ViewerState
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class RecordingInjector : InputInjector {
    override val platform = HostPlatform.DESKTOP
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
    val events = CopyOnWriteArrayList<String>()

    override fun touch(time: Long, pointers: List<TouchPointer>) {
        events += "touch:${pointers.size}"
    }

    override fun navigate(action: NavAction) {
        events += "navigate:$action"
    }

    override fun type(deleteBefore: Int, text: String) {
        events += "type:$text"
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

class EndToEndTest {

    private suspend fun <T> eventually(block: suspend () -> T?): T = withTimeout(30.seconds) {
        var result = block()
        while (result == null) {
            delay(50)
            result = block()
        }
        result
    }

    @Test
    fun `a viewer watches and controls a host over real webrtc`() = runBlocking {
        val engine = DesktopRtcEngine()
        val factory = engine.factory
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("screen", source)
        val media = DesktopCapturedMedia(track, WIDTH, HEIGHT, 1_000_000) { source.dispose() }
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            while (true) {
                val frame = VideoFrame(NativeI420Buffer.allocate(WIDTH, HEIGHT), System.nanoTime())
                source.pushFrame(frame)
                frame.release()
                delay(33)
            }
        }

        val injector = RecordingInjector()
        val host = HostSession(engine, SignalingClient(), DeviceName("Host"), lanServer = EmbeddedLanServer(), inputInjector = injector)
        val viewer = ViewerSession(engine, SignalingClient(), DeviceName("Viewer"))

        try {
            host.start(media, internetServer = null, allowControl = true)
            val live = eventually { host.state.value as? HostState.Live }
            viewer.connect(JoinTarget.Lan("127.0.0.1", live.port), live.pin)
            val pending = eventually { (host.state.value as? HostState.Live)?.pendingViewers?.firstOrNull() }
            host.approve(pending.id)
            val watching = eventually { viewer.state.value as? ViewerState.Watching }

            val frames = AtomicInteger()
            (watching.video as DesktopRemoteVideo).track.addSink(VideoTrackSink { frame ->
                frames.incrementAndGet()
                frame.release()
            })
            eventually { frames.get().takeIf { it >= 5 } }

            eventually { viewer.control.value.takeIf { it.available } }
            viewer.requestControl()
            val requested = eventually {
                (host.state.value as? HostState.Live)?.viewers?.firstOrNull { it.control == ControlRole.REQUESTED }
            }
            host.grantControl(requested.id)
            eventually { viewer.control.value.takeIf { it.role == ControlRole.GRANTED } }

            viewer.pointer(ControlMessage.Pointer(PointerAction.DOWN, 0.5f, 0.5f))
            viewer.keyboard(ControlMessage.Keyboard("a", down = true))
            viewer.navigate(NavAction.BACK)
            eventually { injector.events.takeIf { it.containsAll(listOf("pointer:DOWN", "keyboard:a", "navigate:BACK")) } }
            assertTrue(frames.get() >= 5)
        } finally {
            viewer.close()
            host.stop().join()
            scope.cancel()
            media.release()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }
}
