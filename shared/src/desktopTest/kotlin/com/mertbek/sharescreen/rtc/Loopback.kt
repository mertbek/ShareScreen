package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.media.audio.AudioTrackSink
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
    private val remoteAudio = CompletableDeferred<DesktopRemoteAudio>()
    private val frames = AtomicInteger()
    private val peak = AtomicInteger()

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
                    is RtcEvent.RemoteAudioTrack -> remoteAudio.complete(event.track as DesktopRemoteAudio)
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
        remoteVideo.await().track.addSink(VideoTrackSink { frame ->
            frames.incrementAndGet()
            frame.release()
        })
        viewerChannel = remoteChannel.await()
        viewerChannel.state.first { it == DataChannelState.OPEN }
        hostChannel.state.first { it == DataChannelState.OPEN }
    }

    suspend fun awaitFrames(count: Int) {
        while (frames.get() < count) delay(50)
    }

    suspend fun awaitAudioAbove(level: Int) {
        remoteAudio.await().track.addSink(AudioTrackSink { data, bits, _, _, _ ->
            if (bits == 16) {
                var max = 0
                for (index in 0 until data.size - 1 step 2) {
                    val sample = (data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)
                    max = maxOf(max, kotlin.math.abs(sample.toShort().toInt()))
                }
                peak.accumulateAndGet(max, ::maxOf)
            }
        })
        while (peak.get() < level) delay(50)
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun close() {
        scope.cancel()
        viewer.close()
        host.close()
        media.release()
    }
}
