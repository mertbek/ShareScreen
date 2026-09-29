package com.mertbek.sharescreen.link

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ServerAddressTest {

    @Test
    fun `bare hosts default to secure websockets`() {
        assertEquals("wss://share.example.com", ServerAddress.normalize("share.example.com"))
        assertEquals("wss://share.example.com:8443", ServerAddress.normalize(" share.example.com:8443/ "))
    }

    @Test
    fun `http schemes map to their websocket counterparts`() {
        assertEquals("wss://share.example.com", ServerAddress.normalize("https://share.example.com"))
        assertEquals("ws://10.0.2.2:8080", ServerAddress.normalize("http://10.0.2.2:8080"))
        assertEquals("ws://10.0.2.2:8080", ServerAddress.normalize("WS://10.0.2.2:8080"))
    }

    @Test
    fun `invalid input is rejected`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("ftp://share.example.com"))
        assertNull(ServerAddress.normalize("share example.com"))
        assertNull(ServerAddress.normalize("wss://"))
    }

    @Test
    fun `signaling url appends the websocket path`() {
        assertEquals("wss://share.example.com/ws", ServerAddress.signalingUrl("wss://share.example.com"))
    }
}
