package com.mertbek.sharescreen.control

import com.mertbek.sharescreen.platform.InputInjector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class DesktopInputInjector : InputInjector {
    private val robot: Robot? = if (GraphicsEnvironment.isHeadless()) null else runCatching { Robot() }.getOrNull()
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val heldButtons = mutableSetOf<Int>()
    private val heldKeys = mutableSetOf<Int>()
    private var touchDown = false
    private val pasteClipboard by lazy { PasteClipboard() }

    override val platform = HostPlatform.DESKTOP
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(robot != null)

    @Synchronized
    override fun touch(time: Long, pointers: List<TouchPointer>) {
        val first = pointers.firstOrNull()
        if (first == null) {
            if (touchDown) release(InputEvent.BUTTON1_DOWN_MASK)
            touchDown = false
            return
        }
        move(first.x, first.y)
        if (!touchDown) {
            press(InputEvent.BUTTON1_DOWN_MASK)
            touchDown = true
        }
    }

    @Synchronized
    override fun pointer(message: ControlMessage.Pointer) {
        val robot = robot ?: return
        move(message.x, message.y)
        val mask = when (message.button) {
            PointerButton.LEFT -> InputEvent.BUTTON1_DOWN_MASK
            PointerButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
            PointerButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
        }
        when (message.action) {
            PointerAction.MOVE -> Unit
            PointerAction.DOWN -> press(mask)
            PointerAction.UP -> release(mask)
            PointerAction.SCROLL -> if (message.scrollY != 0f) robot.mouseWheel(message.scrollY.toInt().coerceIn(-MAX_WHEEL, MAX_WHEEL))
        }
    }

    @Synchronized
    override fun keyboard(message: ControlMessage.Keyboard) {
        val robot = robot ?: return
        val code = awtKeyCode(message.key) ?: return
        val modifiers = buildList {
            if (message.ctrl) add(KeyEvent.VK_CONTROL)
            if (message.alt) add(KeyEvent.VK_ALT)
            if (message.shift) add(KeyEvent.VK_SHIFT)
            if (message.meta) add(KeyEvent.VK_META)
        }
        if (message.down) {
            modifiers.forEach { pressKey(it) }
            pressKey(code)
        } else {
            releaseKey(code)
            modifiers.forEach { releaseKey(it) }
        }
        robot.waitForIdle()
    }

    @Synchronized
    override fun type(deleteBefore: Int, text: String) {
        val robot = robot ?: return
        repeat(deleteBefore.coerceIn(0, MAX_EDIT)) { tap(KeyEvent.VK_BACK_SPACE) }
        if (text.isEmpty()) return
        pasteClipboard.put(text.take(MAX_EDIT))
        val shortcut = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        pressKey(shortcut)
        tap(KeyEvent.VK_V)
        releaseKey(shortcut)
        robot.waitForIdle()
    }

    @Synchronized
    override fun press(key: ControlKey) {
        when (key) {
            ControlKey.ENTER -> tap(KeyEvent.VK_ENTER)
        }
    }

    @Synchronized
    override fun navigate(action: NavAction) {
        when (action) {
            NavAction.BACK -> chord(if (isMac) KeyEvent.VK_META else KeyEvent.VK_ALT, if (isMac) KeyEvent.VK_OPEN_BRACKET else KeyEvent.VK_LEFT)
            NavAction.HOME -> chord(KeyEvent.VK_WINDOWS, KeyEvent.VK_D)
            NavAction.RECENTS -> chord(KeyEvent.VK_WINDOWS, KeyEvent.VK_TAB)
        }
    }

    @Synchronized
    override fun releaseInput() {
        heldButtons.toList().forEach { release(it) }
        heldKeys.toList().forEach { releaseKey(it) }
        touchDown = false
    }

    private fun move(fractionX: Float, fractionY: Float) {
        val robot = robot ?: return
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val (x, y) = screenPosition(bounds.x, bounds.y, bounds.width, bounds.height, fractionX, fractionY)
        robot.mouseMove(x, y)
    }

    private fun press(mask: Int) {
        if (heldButtons.add(mask)) robot?.mousePress(mask)
    }

    private fun release(mask: Int) {
        if (heldButtons.remove(mask)) robot?.mouseRelease(mask)
    }

    private fun pressKey(code: Int) {
        if (heldKeys.add(code)) robot?.keyPress(code)
    }

    private fun releaseKey(code: Int) {
        if (heldKeys.remove(code)) robot?.keyRelease(code)
    }

    private fun tap(code: Int) {
        robot?.keyPress(code)
        robot?.keyRelease(code)
    }

    private fun chord(modifier: Int, key: Int) {
        robot?.keyPress(modifier)
        tap(key)
        robot?.keyRelease(modifier)
    }

    private companion object {
        const val MAX_WHEEL = 20
        const val MAX_EDIT = 10_000
    }
}
