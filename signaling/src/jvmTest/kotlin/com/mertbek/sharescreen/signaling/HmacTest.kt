package com.mertbek.sharescreen.signaling

import kotlin.test.Test
import kotlin.test.assertEquals

class HmacTest {

    @Test
    fun `matches the RFC 4231 test vector`() {
        val mac = hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }
}
