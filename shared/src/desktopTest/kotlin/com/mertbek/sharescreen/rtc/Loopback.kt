package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class Loopback(engine: DesktopRtcEngine, private val media: DesktopCapturedMedia) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = engine.createPeer(emptyList())
    private val viewer = engine.createPeer(emptyList())
    private val hostConnected = CompletableDeferred<Unit>()
    private val viewerConnected = CompletableDeferred<Unit>()
    private val remoteVideo = CompletableDeferred<DesktopRemoteVideo>()
    private val remoteChannel = CompletableDeferred<RtcDataChannel>()
    private val frames = AtomicInteger()

    lateinit var hostChannel: RtcDataChannel
        private set

    lateinit var viewerChannel: RtcDataChannel
        private set

    val frameCount: Int get() = frames.get()

    suspend fun connect() {
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
                    is RtcEvent.RemoteVideoTrack -> remoteVideo.complete(event.track as DesktopRemoteVideo)
                    is RtcEvent.RemoteDataChannel -> remoteChannel.complete(event.channel)
                    else -> Unit
                }
            }
        }
        host.addMedia(media)
        hostChannel = host.createDataChannel("control")
        val offer = host.createOffer()
        host.setLocalDescription(offer)
        viewer.setRemoteDescription(offer)
        val answer = viewer.createAnswer()
        viewer.setLocalDescription(answer)
        host.setRemoteDescription(answer)

        hostConnected.await()
        viewerConnected.await()
        remoteVideo.await().track.addSink(VideoTrackSink { frames.incrementAndGet() })
        viewerChannel = remoteChannel.await()
        viewerChannel.state.first { it == DataChannelState.OPEN }
        hostChannel.state.first { it == DataChannelState.OPEN }
    }

    suspend fun awaitFrames(count: Int) {
        while (frames.get() < count) delay(50)
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun close() {
        scope.cancel()
        viewer.close()
        host.close()
        media.release()
    }
}
