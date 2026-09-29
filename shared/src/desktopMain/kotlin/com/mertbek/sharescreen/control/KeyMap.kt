package com.mertbek.sharescreen.control

import java.awt.event.KeyEvent

private val namedKeys = mapOf(
    "Enter" to KeyEvent.VK_ENTER,
    "Backspace" to KeyEvent.VK_BACK_SPACE,
    "Tab" to KeyEvent.VK_TAB,
    "Escape" to KeyEvent.VK_ESCAPE,
    "Delete" to KeyEvent.VK_DELETE,
    "Insert" to KeyEvent.VK_INSERT,
    "Home" to KeyEvent.VK_HOME,
    "End" to KeyEvent.VK_END,
    "PageUp" to KeyEvent.VK_PAGE_UP,
    "PageDown" to KeyEvent.VK_PAGE_DOWN,
    "ArrowLeft" to KeyEvent.VK_LEFT,
    "ArrowRight" to KeyEvent.VK_RIGHT,
    "ArrowUp" to KeyEvent.VK_UP,
    "ArrowDown" to KeyEvent.VK_DOWN,
    "CapsLock" to KeyEvent.VK_CAPS_LOCK,
    "Space" to KeyEvent.VK_SPACE,
)

fun awtKeyCode(key: String): Int? {
    namedKeys[key]?.let { return it }
    if (key.length in 2..3 && key[0] == 'F') {
        val number = key.substring(1).toIntOrNull()
        if (number != null && number in 1..12) return KeyEvent.VK_F1 + number - 1
    }
    if (key.length == 1) {
        val code = KeyEvent.getExtendedKeyCodeForChar(key[0].code)
        if (code != KeyEvent.VK_UNDEFINED) return code
    }
    return null
}

fun screenPosition(x: Int, y: Int, width: Int, height: Int, fractionX: Float, fractionY: Float): Pair<Int, Int> {
    val fx = fractionX.takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 0f
    val fy = fractionY.takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 0f
    return x + (fx * (width - 1)).toInt() to y + (fy * (height - 1)).toInt()
}
