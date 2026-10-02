package com.mertbek.sharescreen.control

data class TextEditResult(val text: String, val cursor: Int)

fun applyTextEdit(text: String, selectionStart: Int, selectionEnd: Int, deleteBefore: Int, insert: String): TextEditResult {
    val a = if (selectionStart in 0..text.length) selectionStart else text.length
    val b = if (selectionEnd in 0..text.length) selectionEnd else a
    val start = minOf(a, b)
    val end = maxOf(a, b)
    val replacesSelection = start < end && (deleteBefore > 0 || insert.isNotEmpty())
    val remaining = if (replacesSelection && deleteBefore > 0) deleteBefore - 1 else deleteBefore
    val deleteFrom = (start - remaining.coerceAtLeast(0)).coerceAtLeast(0)
    val keepFrom = if (replacesSelection) end else start
    return TextEditResult(text.substring(0, deleteFrom) + insert + text.substring(keepFrom), deleteFrom + insert.length)
}

object TypingBox {
    const val EMPTY = "​"

    fun edit(old: String, new: String): Pair<ControlMessage.Type?, String> {
        val oldTyped = old.replace(EMPTY, "")
        val newTyped = new.replace(EMPTY, "")
        val extraBackspace = if (EMPTY !in new && newTyped == oldTyped) 1 else 0
        val common = oldTyped.commonPrefixWith(newTyped).length
        val deleteBefore = oldTyped.length - common + extraBackspace
        val insert = newTyped.substring(common)
        val edit = if (deleteBefore > 0 || insert.isNotEmpty()) ControlMessage.Type(deleteBefore, insert) else null
        return edit to EMPTY + newTyped
    }
}

/**
 * The text a key types into a phone's text field, from the character the viewer's keyboard gave it, or null when
 * it types nothing there: control characters, and shortcuts held with Ctrl or the command key. Ctrl and Alt
 * together are AltGr on Windows, which types characters such as @.
 */
fun typedText(codePoint: Int, ctrl: Boolean, alt: Boolean, meta: Boolean): String? {
    if (meta || (ctrl && !alt)) return null
    val printable = codePoint in 0x20..0x10FFFF && codePoint != 0x7F && codePoint !in 0x80..0x9F &&
        codePoint !in 0xD800..0xDFFF && codePoint !in 0xFFF0..0xFFFF
    if (!printable) return null
    if (codePoint < 0x10000) return codePoint.toChar().toString()
    val offset = codePoint - 0x10000
    return charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
}
