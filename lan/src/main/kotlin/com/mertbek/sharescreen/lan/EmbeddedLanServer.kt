package com.mertbek.sharescreen.lan

import com.mertbek.sharescreen.platform.LanServer
import com.mertbek.sharescreen.server.EmbeddedSignalingServer

class EmbeddedLanServer : LanServer {
    private val server = EmbeddedSignalingServer()

    override suspend fun start(hostSecret: String): Int = server.start(hostSecret)

    override suspend fun stop() = server.stop()
}
