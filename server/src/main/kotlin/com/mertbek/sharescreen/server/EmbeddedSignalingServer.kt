package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.signaling.RoomManager
import com.mertbek.sharescreen.signaling.SignalingConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EmbeddedSignalingServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start(hostSecret: String, maxViewers: Int = DEFAULT_MAX_VIEWERS): Int = mutex.withLock {
        check(server == null) { "Signaling server is already running" }
        val roomManager = RoomManager(
            SignalingConfig(singleRoom = true, hostSecret = hostSecret, maxViewersPerRoom = maxViewers)
        )
        val server = scope.embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            signalingModule(roomManager)
        }
        server.startSuspend(wait = false)
        this.server = server
        server.engine.resolvedConnectors().first().port
    }

    suspend fun stop() = mutex.withLock {
        val server = server ?: return@withLock
        this.server = null
        withContext(Dispatchers.IO) {
            server.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
        }
    }

    companion object {
        const val DEFAULT_MAX_VIEWERS = 4
        private const val STOP_GRACE_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 1_500L
    }
}
