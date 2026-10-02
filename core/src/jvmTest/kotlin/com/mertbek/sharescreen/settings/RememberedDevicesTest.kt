package com.mertbek.sharescreen.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RememberedDevicesTest {

    private val hostStore = MapSettings()
    private val host = RememberedDevices(hostStore)
    private val viewer = RememberedDevices(MapSettings())

    @Test
    fun `a remembered viewer comes back with a pass the host takes once`() {
        viewer.rememberHost(host.rememberViewer("Tablet"))
        assertTrue(viewer.knowsHost(host.hostId))

        val pass = assertNotNull(viewer.passFor(host.hostId))
        assertEquals("Tablet", host.admit(pass)?.name)
        assertNull(host.admit(pass), "the same pass does not work twice")
        assertNotNull(host.admit(assertNotNull(viewer.passFor(host.hostId))))
    }

    @Test
    fun `a pass is refused when it is forged or the viewer was forgotten`() {
        val invitation = host.rememberViewer("Tablet")
        viewer.rememberHost(invitation.copy(secret = "not the secret"))
        assertNull(host.admit(assertNotNull(viewer.passFor(host.hostId))))

        viewer.rememberHost(invitation)
        host.forgetViewer(invitation.key)
        assertNull(host.admit(assertNotNull(viewer.passFor(host.hostId))))
        assertTrue(host.viewers.value.isEmpty())
    }

    @Test
    fun `everything survives a restart`() {
        val viewerStore = MapSettings()
        val first = RememberedDevices(viewerStore)
        first.rememberHost(host.rememberViewer("Tablet"))
        host.admit(assertNotNull(first.passFor(host.hostId)))

        val again = RememberedDevices(viewerStore)
        val hostAgain = RememberedDevices(hostStore)
        assertEquals(host.hostId, hostAgain.hostId)
        assertEquals(listOf("Tablet"), hostAgain.viewers.value.map { it.name })
        assertNotNull(hostAgain.admit(assertNotNull(again.passFor(host.hostId))), "counters carry on after a restart")
    }

    @Test
    fun `control without asking is kept per viewer and can be taken back`() {
        val tablet = host.rememberViewer("Tablet", control = true)
        val phone = host.rememberViewer("Phone")
        assertTrue(host.controlAllowed(tablet.key))
        assertFalse(host.controlAllowed(phone.key))

        host.allowControl(tablet.key, false)
        host.allowControl(phone.key, true)
        val again = RememberedDevices(hostStore)
        assertFalse(again.controlAllowed(tablet.key))
        assertTrue(again.controlAllowed(phone.key))
        assertFalse(again.controlAllowed("unknown"))
    }

    @Test
    fun `a forgotten host gets no pass`() {
        viewer.rememberHost(host.rememberViewer("Tablet"))
        viewer.forgetHost(host.hostId)
        assertFalse(viewer.knowsHost(host.hostId))
        assertNull(viewer.passFor(host.hostId))
    }

    @Test
    fun `damaged storage starts empty`() {
        val store = MapSettings().apply {
            putString("remembered_viewers", "{not json")
            putString("remembered_hosts", "[1, 2]")
        }
        val devices = RememberedDevices(store)
        assertTrue(devices.viewers.value.isEmpty())
        assertFalse(devices.knowsHost("anything"))
    }
}
