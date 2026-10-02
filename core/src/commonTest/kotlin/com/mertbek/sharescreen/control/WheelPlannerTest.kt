package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class WheelPlannerTest {

    private val wheel = WheelPlanner(density = 1f, slop = 8f)

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) = WheelSwipe(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY))

    @Test
    fun `a notch down drags the content up under the pointer`() {
        wheel.add(ScreenPoint(500f, 1000f), 0f, 1f)
        assertEquals(swipe(500f, 1000f, 500f, 928f), wheel.next(1000, 2000))
        assertNull(wheel.next(1000, 2000))
    }

    @Test
    fun `a notch up drags it down and shift with the wheel drags it sideways`() {
        wheel.add(ScreenPoint(500f, 1000f), 0f, -1f)
        assertEquals(swipe(500f, 1000f, 500f, 1072f), wheel.next(1000, 2000))
        wheel.add(ScreenPoint(500f, 1000f), 1f, 0f)
        assertEquals(swipe(500f, 1000f, 428f, 1000f), wheel.next(1000, 2000))
    }

    @Test
    fun `swipes keep clear of the screen's edges`() {
        wheel.add(ScreenPoint(2f, 1990f), 0f, 1f)
        assertEquals(swipe(64f, 1936f, 64f, 1864f), wheel.next(1000, 2000))
        wheel.add(ScreenPoint(999f, 5f), 0f, 1f)
        assertEquals(swipe(936f, 136f, 936f, 64f), wheel.next(1000, 2000))
        wheel.add(ScreenPoint(500f, 1990f), 0f, -1f)
        assertEquals(swipe(500f, 1864f, 500f, 1936f), wheel.next(1000, 2000))
    }

    @Test
    fun `a long turn plays out over several swipes`() {
        wheel.add(ScreenPoint(500f, 1000f), 0f, 10f)
        assertEquals(swipe(500f, 1000f, 500f, 520f), wheel.next(1000, 2000))
        assertEquals(swipe(500f, 1000f, 500f, 824f), wheel.next(1000, 2000))
        assertNull(wheel.next(1000, 2000))
    }

    @Test
    fun `a touchpad's small steps add up before a swipe`() {
        wheel.add(ScreenPoint(500f, 1000f), 0f, 0.1f)
        assertNull(wheel.next(1000, 2000))
        wheel.add(ScreenPoint(500f, 1000f), 0f, 0.2f)
        val next = wheel.next(1000, 2000)!!
        assertEquals(1000f - 0.3f * 64f - 8f, next.to.y, 0.01f)
    }

    @Test
    fun `nonsense from the viewer and dropped turns move nothing`() {
        wheel.add(ScreenPoint(500f, 1000f), Float.NaN, Float.POSITIVE_INFINITY)
        assertNull(wheel.next(1000, 2000))
        wheel.add(ScreenPoint(500f, 1000f), 0f, 3f)
        wheel.clear()
        assertNull(wheel.next(1000, 2000))
    }
}
