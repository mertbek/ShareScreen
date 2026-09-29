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
