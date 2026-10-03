package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class TouchMouseTest {

    private val mouse = TouchMouse(slop = 10f)

    /** A finger at [x], [y] on a 1024 by 1024 viewer showing the whole picture. */
    private fun finger(id: Long, x: Float, y: Float) = Fingertip(id, x, y, x / 1024f, y / 1024f)

    private fun pointer(action: PointerAction, x: Float, y: Float, button: PointerButton = PointerButton.LEFT) =
        ControlMessage.Pointer(action, x / 1024f, y / 1024f, button)

    private fun wheel(x: Float, y: Float, notches: Int) =
        ControlMessage.Pointer(PointerAction.SCROLL, x / 1024f, y / 1024f, scrollY = notches.toFloat())

    @Test
    fun `a tap clicks where the finger first touched`() {
        assertEquals(listOf(pointer(PointerAction.MOVE, 100f, 200f)), mouse.update(0, listOf(finger(1, 100f, 200f))))
        assertEquals(emptyList(), mouse.update(40, listOf(finger(1, 104f, 203f))))
        assertEquals(
            listOf(pointer(PointerAction.DOWN, 100f, 200f), pointer(PointerAction.UP, 100f, 200f)),
            mouse.update(90, emptyList()),
        )
        assertNull(mouse.holdDeadline)
    }

    @Test
    fun `a drag holds the left button from where it started`() {
        mouse.update(0, listOf(finger(1, 100f, 200f)))
        assertEquals(
            listOf(pointer(PointerAction.DOWN, 100f, 200f), pointer(PointerAction.MOVE, 100f, 230f)),
            mouse.update(50, listOf(finger(1, 100f, 230f))),
        )
        assertEquals(listOf(pointer(PointerAction.MOVE, 100f, 300f)), mouse.update(80, listOf(finger(1, 100f, 300f))))
        assertEquals(emptyList(), mouse.update(90, listOf(finger(1, 100f, 300f), finger(2, 500f, 500f))))
        assertEquals(listOf(pointer(PointerAction.UP, 100f, 300f)), mouse.update(120, listOf(finger(2, 500f, 500f))))
        assertEquals(emptyList(), mouse.update(150, emptyList()))
        assertNull(mouse.holdDeadline)
    }

    @Test
    fun `a finger held still for a second right clicks and does nothing more`() {
        mouse.update(0, listOf(finger(1, 100f, 200f)))
        assertEquals(1_000L, mouse.holdDeadline)
        assertEquals(emptyList(), mouse.hold(999))
        val right = listOf(
            pointer(PointerAction.DOWN, 100f, 200f, PointerButton.RIGHT),
            pointer(PointerAction.UP, 100f, 200f, PointerButton.RIGHT),
        )
        assertEquals(right, mouse.hold(1_000))
        assertNull(mouse.holdDeadline)
        assertEquals(emptyList(), mouse.update(1_200, listOf(finger(1, 300f, 400f))))
        assertEquals(emptyList(), mouse.update(1_300, emptyList()))

        mouse.update(2_000, listOf(finger(1, 100f, 200f)))
        assertEquals(right, mouse.update(3_100, listOf(finger(1, 102f, 201f))), "held through small moves")
    }

    @Test
    fun `two fingers moving up and down turn the wheel`() {
        assertEquals(emptyList(), mouse.update(0, listOf(finger(1, 400f, 512f), finger(2, 600f, 512f))))
        assertEquals(listOf(wheel(500f, 512f, 2)), mouse.update(50, listOf(finger(1, 400f, 384f), finger(2, 600f, 384f))))
        assertEquals(emptyList(), mouse.update(60, listOf(finger(1, 400f, 368f), finger(2, 600f, 368f))))
        assertEquals(listOf(wheel(500f, 512f, -1)), mouse.update(80, listOf(finger(1, 400f, 448f), finger(2, 600f, 448f))))
        assertEquals(emptyList(), mouse.update(100, emptyList()))
        assertNull(mouse.holdDeadline)
    }

    @Test
    fun `a second finger soon after the first scrolls instead of clicking`() {
        mouse.update(0, listOf(finger(1, 400f, 512f)))
        assertEquals(emptyList(), mouse.update(30, listOf(finger(1, 400f, 512f), finger(2, 600f, 512f))))
        assertNull(mouse.holdDeadline)
        assertEquals(listOf(wheel(500f, 512f, 1)), mouse.update(60, listOf(finger(1, 400f, 448f), finger(2, 600f, 448f))))
        assertEquals(emptyList(), mouse.update(90, listOf(finger(1, 400f, 448f))), "a finger lifting is no scroll")
        assertEquals(emptyList(), mouse.update(120, emptyList()))
    }
}
