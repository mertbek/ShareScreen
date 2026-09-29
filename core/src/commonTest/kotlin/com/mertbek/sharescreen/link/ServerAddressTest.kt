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
    fun `plain websockets are only accepted for local addresses`() {
        assertEquals("ws://192.168.1.5:8080", ServerAddress.normalize("ws://192.168.1.5:8080"))
        assertEquals("ws://172.16.0.1", ServerAddress.normalize("ws://172.16.0.1"))
        assertEquals("ws://localhost:8080", ServerAddress.normalize("http://localhost:8080"))
        assertEquals("ws://printer.local", ServerAddress.normalize("ws://printer.local"))
        assertNull(ServerAddress.normalize("ws://share.example.com"))
        assertNull(ServerAddress.normalize("http://share.example.com"))
        assertNull(ServerAddress.normalize("ws://8.8.8.8"))
        assertNull(ServerAddress.normalize("ws://172.32.0.1"))
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
