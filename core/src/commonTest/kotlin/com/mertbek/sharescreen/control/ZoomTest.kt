package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test

class ZoomTest {

    private val delta = 1e-5f

    @Test
    fun `the whole picture is shown by default`() {
        val zoom = Zoom()
        assertFalse(zoom.isZoomed)
        assertEquals(0.3f, zoom.pictureX(0.3f), delta)
        assertEquals(0.7f, zoom.pictureY(0.7f), delta)
    }

    @Test
    fun `pinching keeps the picture point under the fingers`() {
        val zoom = Zoom().transformed(factor = 2f, fromX = 0.25f, fromY = 0.5f, toX = 0.25f, toY = 0.5f)
        assertEquals(2f, zoom.scale, delta)
        assertEquals(0.25f, zoom.pictureX(0.25f), delta)
        assertEquals(0.5f, zoom.pictureY(0.5f), delta)
    }

    @Test
    fun `dragging moves the picture with the finger`() {
        val zoomed = Zoom(scale = 2f, left = 0.25f, top = 0.25f)
        val moved = zoomed.transformed(factor = 1f, fromX = 0.5f, fromY = 0.5f, toX = 0.3f, toY = 0.5f)
        assertEquals(zoomed.pictureX(0.5f), moved.pictureX(0.3f), delta)
        assertEquals(zoomed.top, moved.top, delta)
    }

    @Test
    fun `the picture cannot be moved past its edges or zoomed out below full size`() {
        val atEdge = Zoom(scale = 2f).transformed(factor = 1f, fromX = 0.1f, fromY = 0.1f, toX = 0.9f, toY = 0.9f)
        assertEquals(0f, atEdge.left, delta)
        assertEquals(0f, atEdge.top, delta)

        val zoomedOut = Zoom(scale = 2f, left = 0.5f, top = 0.5f).transformed(0.1f, 0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(Zoom(), zoomedOut)

        assertEquals(Zoom.MAX_SCALE, Zoom().transformed(100f, 0.5f, 0.5f, 0.5f, 0.5f).scale, delta)
    }

    @Test
    fun `double tap zooms in on the point and back out`() {
        val zoomed = Zoom().toggled(0.8f, 0.2f)
        assertEquals(Zoom.DOUBLE_TAP_SCALE, zoomed.scale, delta)
        assertEquals(0.8f, zoomed.pictureX(0.8f), delta)
        assertEquals(Zoom(), zoomed.toggled(0.5f, 0.5f))
    }
}
