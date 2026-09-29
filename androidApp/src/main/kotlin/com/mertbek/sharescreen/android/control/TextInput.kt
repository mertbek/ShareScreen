package com.mertbek.sharescreen.android.control

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mertbek.sharescreen.control.applyTextEdit

internal class TextInput {

    private var lastField: AccessibilityNodeInfo? = null
    private var lastText = ""
    private var lastCursor = 0
    private var lastEditTime = 0L

    fun type(service: AccessibilityService, deleteBefore: Int, insert: String) {
        val field = focusedField(service) ?: return
        val isPassword = field.isPassword
        val shown = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val sameField = field == lastField
        val recent = sameField && SystemClock.uptimeMillis() - lastEditTime < SETTLE_MILLIS
        val edit = when {
            isPassword -> {
                val current = if (recent || (sameField && lastText.length == shown.length)) lastText else ""
                applyTextEdit(current, current.length, current.length, deleteBefore, insert)
            }
            recent -> applyTextEdit(lastText, lastCursor, lastCursor, deleteBefore, insert)
            else -> applyTextEdit(shown, field.textSelectionStart, field.textSelectionEnd, deleteBefore, insert)
        }

        val textArguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArguments)) return
        lastField = field
        lastText = edit.text
        lastCursor = edit.cursor
        lastEditTime = SystemClock.uptimeMillis()
        if (!isPassword && edit.cursor != edit.text.length) {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        }
    }

    fun enter(service: AccessibilityService) {
        val field = focusedField(service) ?: return
        lastEditTime = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (field.actionList.any { it.id == imeEnter.id } && field.performAction(imeEnter.id)) return
        }
        if (field.isMultiLine) type(service, deleteBefore = 0, insert = "\n")
    }

    private fun focusedField(service: AccessibilityService): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isFocusedEditable() }?.let { return it }
        lastFound?.takeIf { it.isFocusedEditable() }?.let { return it }
        val root = service.rootInActiveWindow ?: return null
        return findFocusedEditable(root).also {
            lastFound = it
            if (it == null) Log.d(TAG, "No focused text field to type into")
        }
    }

    private var lastFound: AccessibilityNodeInfo? = null

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES_SEARCHED) {
            val node = queue.removeFirst()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !node.refresh()) continue
            if (node.isFocused && node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun AccessibilityNodeInfo.isFocusedEditable(): Boolean = refresh() && isFocused && isEditable

    private companion object {
        const val TAG = "TextInput"
        const val MAX_NODES_SEARCHED = 2_000
        const val SETTLE_MILLIS = 1_000L
    }
}
