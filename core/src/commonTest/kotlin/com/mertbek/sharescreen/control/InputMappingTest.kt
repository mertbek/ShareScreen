package com.mertbek.sharescreen.control

import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.session.JoinTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class InputMappingTest {

    @Test
    fun `points map through the fitted picture without zoom`() {
        val fit = fitVideo(1000f, 1000f, 1000, 500)
        val (x, y) = mapToPicture(fit, Zoom(), 500f, 500f)
        assertEquals(0.5f, x, 0.001f)
        assertEquals(0.5f, y, 0.001f)
    }

    @Test
    fun `zoom narrows the mapped area`() {
        val fit = fitVideo(1000f, 1000f, 1000, 1000)
        val zoom = Zoom(scale = 2f, left = 0.5f, top = 0.5f)
        val (x, y) = mapToPicture(fit, zoom, 0f, 1000f)
        assertEquals(0.5f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
    }

    @Test
    fun `links become join targets`() {
        assertEquals(JoinTarget.Lan("10.0.0.2", 8080), ConnectLink.Lan("10.0.0.2", 8080, "123456").toJoinTarget())
        assertEquals(
            JoinTarget.Internet("wss://example.com", "ABC234"),
            ConnectLink.Internet("wss://example.com", "ABC234", "123456").toJoinTarget(),
        )
    }
}
