package com.mertbek.sharescreen.control

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class PasteClipboardTest {

    private fun Clipboard.text(): String = getData(DataFlavor.stringFlavor) as String

    @Test
    fun `the previous text comes back after pasting`() {
        val clipboard = Clipboard("test").apply { setContents(StringSelection("mine"), null) }
        val paste = PasteClipboard(clipboard, restoreDelayMillis = 100)

        paste.put("typed")
        assertEquals("typed", clipboard.text())
        Thread.sleep(500)
        assertEquals("mine", clipboard.text())
    }

    @Test
    fun `quick pastes in a row restore the original once`() {
        val clipboard = Clipboard("test").apply { setContents(StringSelection("mine"), null) }
        val paste = PasteClipboard(clipboard, restoreDelayMillis = 150)

        paste.put("a")
        Thread.sleep(60)
        paste.put("b")
        assertEquals("b", clipboard.text())
        Thread.sleep(600)
        assertEquals("mine", clipboard.text())
    }

    @Test
    fun `typed text does not stay behind when the clipboard was empty`() {
        val clipboard = Clipboard("test")
        val paste = PasteClipboard(clipboard, restoreDelayMillis = 100)

        paste.put("secret")
        Thread.sleep(500)
        assertEquals("", clipboard.text())
    }
}
