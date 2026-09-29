package com.mertbek.sharescreen.signaling

import com.mertbek.sharescreen.signaling.SignalMessage.Hello
import com.mertbek.sharescreen.signaling.SignalMessage.Ice
import com.mertbek.sharescreen.signaling.SignalMessage.SessionEnded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalCodecTest {

    @Test
    fun `messages round-trip with a type discriminator`() {
        val messages = listOf(
            Hello(PeerRole.VIEWER, "Pixel", pin = "123456"),
            Ice(to = "host", candidate = "candidate:1 1 udp 1 192.168.1.2 5000 typ host", sdpMid = "0", sdpMLineIndex = 0),
            SessionEnded,
        )
        for (message in messages) {
            assertEquals(message, SignalCodec.decode(SignalCodec.encode(message)))
        }
        assertTrue(SignalCodec.encode(SessionEnded).contains("\"type\":\"session_ended\""))
    }

    @Test
    fun `protocol version is always encoded`() {
        assertTrue(SignalCodec.encode(Hello(PeerRole.HOST, "Host")).contains("\"protocolVersion\":$PROTOCOL_VERSION"))
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val decoded = SignalCodec.decode("""{"type":"peer_left","peerId":"p1","reason":"timeout"}""")
        assertEquals(SignalMessage.PeerLeft("p1"), decoded)
    }
}
