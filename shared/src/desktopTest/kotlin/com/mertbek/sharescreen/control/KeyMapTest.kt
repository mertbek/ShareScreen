package com.mertbek.sharescreen.control

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMapTest {

    @Test
    fun `named keys map to virtual key codes`() {
        assertEquals(KeyEvent.VK_ENTER, awtKeyCode("Enter"))
        assertEquals(KeyEvent.VK_LEFT, awtKeyCode("ArrowLeft"))
        assertEquals(KeyEvent.VK_F5, awtKeyCode("F5"))
        assertEquals(KeyEvent.VK_F12, awtKeyCode("F12"))
    }

    @Test
    fun `characters map to their key codes`() {
        assertEquals(KeyEvent.VK_A, awtKeyCode("a"))
        assertEquals(KeyEvent.VK_5, awtKeyCode("5"))
    }

    @Test
    fun `unknown keys are ignored`() {
        assertNull(awtKeyCode("MediaPlayPause"))
        assertNull(awtKeyCode("F13"))
        assertNull(awtKeyCode(""))
    }

    @Test
    fun `fractions map inside the screen and are clamped`() {
        assertEquals(100 to 50, screenPosition(100, 50, 1920, 1080, 0f, 0f))
        assertEquals(100 + 1919 to 50 + 1079, screenPosition(100, 50, 1920, 1080, 1f, 1f))
        assertEquals(100 to 50, screenPosition(100, 50, 1920, 1080, -3f, Float.NaN))
    }
}
