package com.mertbek.sharescreen.control

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PasteClipboard(
    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard,
    private val restoreDelayMillis: Long = RESTORE_DELAY_MILLIS,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "clipboard-restore").apply { isDaemon = true }
    }
    private var saved: String? = null
    private var hasSaved = false
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    fun put(text: String) {
        if (!hasSaved) {
            saved = currentText()
            hasSaved = true
        }
        clipboard.setContents(StringSelection(text), null)
        pending?.cancel(false)
        pending = executor.schedule({ restore() }, restoreDelayMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun restore() {
        runCatching { clipboard.setContents(StringSelection(saved.orEmpty()), null) }
        saved = null
        hasSaved = false
    }

    private fun currentText(): String? = try {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val RESTORE_DELAY_MILLIS = 600L
    }
}
