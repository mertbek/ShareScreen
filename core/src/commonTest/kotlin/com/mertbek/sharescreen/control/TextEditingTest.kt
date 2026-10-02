package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class TextEditingTest {

    @Test
    fun `inserts at the cursor`() {
        assertEquals(TextEditResult("hello world", 7), applyTextEdit("hello orld", 6, 6, 0, "w"))
    }

    @Test
    fun `backspace deletes before the cursor, never past the start`() {
        assertEquals(TextEditResult("helo", 3), applyTextEdit("hello", 4, 4, 1, ""))
        assertEquals(TextEditResult("llo", 0), applyTextEdit("hello", 2, 2, 5, ""))
    }

    @Test
    fun `an unknown cursor means the end of the text`() {
        assertEquals(TextEditResult("hi!", 3), applyTextEdit("hi", -1, -1, 0, "!"))
    }

    @Test
    fun `typing replaces the selection and the first backspace deletes it`() {
        assertEquals(TextEditResult("a X d", 3), applyTextEdit("a bc d", 2, 4, 0, "X"))
        assertEquals(TextEditResult("a  d", 2), applyTextEdit("a bc d", 4, 2, 1, ""))
        assertEquals(TextEditResult("a d", 1), applyTextEdit("a bc d", 2, 4, 2, ""))
    }

    @Test
    fun `typing box sends what changed at its end`() {
        val empty = TypingBox.EMPTY
        assertEquals(ControlMessage.Type(0, "h") to empty + "h", TypingBox.edit(empty, empty + "h"))
        assertEquals(ControlMessage.Type(1, "") to empty + "h", TypingBox.edit(empty + "hi", empty + "h"))
        assertEquals(ControlMessage.Type(2, "he") to empty + "the the", TypingBox.edit(empty + "the teh", empty + "the the"))
    }

    @Test
    fun `backspace in the empty box still reaches the other device`() {
        assertEquals(ControlMessage.Type(1, "") to TypingBox.EMPTY, TypingBox.edit(TypingBox.EMPTY, ""))
    }

    @Test
    fun `replacing everything does not delete more than was typed`() {
        val (edit, box) = TypingBox.edit(TypingBox.EMPTY + "abc", "x")
        assertEquals(ControlMessage.Type(3, "x"), edit)
        assertEquals(TypingBox.EMPTY + "x", box)
    }

    @Test
    fun `no edit when nothing changed`() {
        assertNull(TypingBox.edit(TypingBox.EMPTY + "a", TypingBox.EMPTY + "a").first)
    }

    @Test
    fun `keys type their character and shortcuts type nothing`() {
        assertEquals("a", typedText('a'.code, ctrl = false, alt = false, meta = false))
        assertEquals(" ", typedText(' '.code, ctrl = false, alt = false, meta = false))
        assertEquals("ğ", typedText('ğ'.code, ctrl = false, alt = false, meta = false))
        assertEquals("@", typedText('@'.code, ctrl = true, alt = true, meta = false))
        assertEquals("€", typedText('€'.code, ctrl = false, alt = true, meta = false))
        assertEquals("😀", typedText(0x1F600, ctrl = false, alt = false, meta = false))
        assertNull(typedText('c'.code, ctrl = true, alt = false, meta = false))
        assertNull(typedText('v'.code, ctrl = false, alt = false, meta = true))
        assertNull(typedText(0x03, ctrl = true, alt = false, meta = false))
        assertNull(typedText('\b'.code, ctrl = false, alt = false, meta = false))
        assertNull(typedText(0x7F, ctrl = false, alt = false, meta = false))
        assertNull(typedText(0xFFFF, ctrl = false, alt = false, meta = false))
        assertNull(typedText(0, ctrl = false, alt = false, meta = false))
    }
}
