package com.mertbek.sharescreen.ui

import com.mertbek.sharescreen.ui.components.qrModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrModulesTest {

    @Test
    fun `the code is square and starts with the finder pattern`() {
        val modules = qrModules("https://example.com/join#r=ABC234&p=123456")
        assertTrue(modules.size >= 21)
        assertTrue(modules.all { it.size == modules.size })
        assertTrue(modules[0].take(7).all { it })
        assertTrue(modules.take(7).all { it[0] })
    }

    @Test
    fun `longer content needs a bigger code`() {
        assertTrue(qrModules("a".repeat(200)).size > qrModules("a").size)
        assertEquals(qrModules("same"), qrModules("same"))
    }
}
