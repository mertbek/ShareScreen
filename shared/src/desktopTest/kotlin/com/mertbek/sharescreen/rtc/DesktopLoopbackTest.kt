package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopLoopbackTest {

    @Test
    fun `video and control messages flow between two peers`() = runBlocking {
        val engine = DesktopRtcEngine()
        val factory = engine.factory
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("screen", source)
        val media = DesktopCapturedMedia(track, WIDTH, HEIGHT, 1_000_000) { source.dispose() }

        val host = engine.createPeer(emptyList())
        val viewer = engine.createPeer(emptyList())
        val scope = CoroutineScope(Dispatchers.Default)

        val viewerVideo = CompletableDeferred<DesktopRemoteVideo>()
        val viewerChannel = CompletableDeferred<RtcDataChannel>()
        val hostConnected = CompletableDeferred<Unit>()
        val viewerConnected = CompletableDeferred<Unit>()

        scope.launch {
            host.events.collect { event ->
                when (event) {
                    is RtcEvent.LocalIceCandidate -> viewer.addRemoteIceCandidate(event.candidate)
                    is RtcEvent.ConnectionState -> if (event.state == PeerConnectionState.CONNECTED) hostConnected.complete(Unit)
                    else -> Unit
                }
            }
        }
        scope.launch {
            viewer.events.collect { event ->
                when (event) {
                    is RtcEvent.LocalIceCandidate -> host.addRemoteIceCandidate(event.candidate)
                    is RtcEvent.ConnectionState -> if (event.state == PeerConnectionState.CONNECTED) viewerConnected.complete(Unit)
                    is RtcEvent.RemoteVideoTrack -> viewerVideo.complete(event.track as DesktopRemoteVideo)
                    is RtcEvent.RemoteDataChannel -> viewerChannel.complete(event.channel)
                    else -> Unit
                }
            }
        }

        host.addMedia(media)
        val hostChannel = host.createDataChannel("control")
        val offer = host.createOffer()
        host.setLocalDescription(offer)
        viewer.setRemoteDescription(offer)
        val answer = viewer.createAnswer()
        viewer.setLocalDescription(answer)
        host.setRemoteDescription(answer)

        val frames = AtomicInteger()
        val pump = scope.launch {
            while (true) {
                val buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT)
                val frame = VideoFrame(buffer, System.nanoTime())
                source.pushFrame(frame)
                frame.release()
                delay(33)
            }
        }

        withTimeout(30.seconds) {
            hostConnected.await()
            viewerConnected.await()
            viewerVideo.await().track.addSink(VideoTrackSink { frame ->
                if (frame.buffer.width == WIDTH && frame.buffer.height == HEIGHT) frames.incrementAndGet()
            })
            val channel = viewerChannel.await()
            channel.state.first { it == DataChannelState.OPEN }
            hostChannel.state.first { it == DataChannelState.OPEN }
            assertTrue(hostChannel.send("hello"))
            assertEquals("hello", channel.messages.first())
            while (frames.get() < 5) delay(50)
        }

        pump.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        viewer.close()
        host.close()
        media.release()
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }
}
