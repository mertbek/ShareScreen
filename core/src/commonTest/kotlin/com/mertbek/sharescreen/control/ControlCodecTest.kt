package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ControlCodecTest {

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            ControlMessage.Status(available = true, role = ControlRole.GRANTED),
            ControlMessage.Request,
            ControlMessage.Release,
            ControlMessage.Touch(time = 123_456, pointers = listOf(TouchPointer(7, 0.25f, 0.75f), TouchPointer(8, 1f, 0f))),
            ControlMessage.Touch(time = 123_500, pointers = emptyList()),
            ControlMessage.Navigate(NavAction.RECENTS),
            ControlMessage.Type(deleteBefore = 2, text = "héllo 👋"),
            ControlMessage.Press(ControlKey.ENTER),
            ControlMessage.Status(available = true, role = ControlRole.NONE, platform = HostPlatform.DESKTOP),
            ControlMessage.Pointer(PointerAction.DOWN, 0.5f, 0.25f, PointerButton.RIGHT),
            ControlMessage.Pointer(PointerAction.SCROLL, 0.1f, 0.9f, scrollX = 0f, scrollY = -3f),
            ControlMessage.Keyboard("Enter", down = true),
            ControlMessage.Keyboard("c", down = false, ctrl = true, shift = true),
        )
        for (message in messages) assertEquals(message, ControlCodec.decode(ControlCodec.encode(message)))
    }

    @Test
    fun `uses readable type names on the wire`() {
        assertEquals(
            """{"type":"navigate","action":"back"}""",
            ControlCodec.encode(ControlMessage.Navigate(NavAction.BACK)),
        )
    }

    @Test
    fun `unknown and malformed messages are dropped instead of failing`() {
        assertNull(ControlCodec.decode("""{"type":"scroll_wheel","delta":3}"""))
        assertNull(ControlCodec.decode("""{"type":"navigate","action":"power_off"}"""))
        assertNull(ControlCodec.decode("not json"))
    }

    @Test
    fun `hosts that predate the platform field are android`() {
        assertEquals(
            ControlMessage.Status(available = true, role = ControlRole.GRANTED, platform = HostPlatform.ANDROID),
            ControlCodec.decode("""{"type":"status","available":true,"role":"granted"}"""),
        )
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        assertEquals(
            ControlMessage.Status(available = false, role = ControlRole.NONE),
            ControlCodec.decode("""{"type":"status","available":false,"role":"none","reason":"x"}"""),
        )
    }
}
