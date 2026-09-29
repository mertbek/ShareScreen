package com.mertbek.sharescreen.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFitTest {

    @Test
    fun `a wide video is centered with bars above and below`() {
        val fit = fitVideo(1000f, 1000f, 1920, 1080)
        assertEquals(0f, fit.left, 0.01f)
        assertEquals(1000f, fit.width, 0.01f)
        assertEquals(562.5f, fit.height, 0.01f)
        assertEquals(218.75f, fit.top, 0.01f)
    }

    @Test
    fun `a tall video is centered with bars at the sides`() {
        val fit = fitVideo(1000f, 500f, 500, 1000)
        assertEquals(250f, fit.width, 0.01f)
        assertEquals(500f, fit.height, 0.01f)
        assertEquals(375f, fit.left, 0.01f)
    }

    @Test
    fun `points map to fractions of the picture and are clamped`() {
        val fit = fitVideo(1000f, 1000f, 1000, 500)
        assertEquals(0.5f, fit.fractionX(500f), 0.001f)
        assertEquals(0f, fit.fractionY(0f))
        assertEquals(1f, fit.fractionY(1000f))
    }

    @Test
    fun `hit testing excludes the bars`() {
        val fit = fitVideo(1000f, 1000f, 1000, 500)
        assertTrue(fit.contains(500f, 500f))
        assertFalse(fit.contains(500f, 10f))
    }

    @Test
    fun `an unknown video size gives an empty rect`() {
        assertEquals(0f, fitVideo(1000f, 1000f, 0, 0).width)
    }
}
