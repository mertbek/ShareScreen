package com.mertbek.sharescreen.client

import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultHttpClientTest {

    @Test
    fun `a connection is pinged so a silent one is noticed`() {
        val client = defaultHttpClient()
        assertEquals(20_000, client.plugin(WebSockets).pingIntervalMillis)
        client.close()
    }
}
