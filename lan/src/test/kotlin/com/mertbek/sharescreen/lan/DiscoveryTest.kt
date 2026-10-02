package com.mertbek.sharescreen.lan

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DiscoveryTest {

    @Test
    fun `an announced host is found`() = runBlocking {
        assumeTrue(NetworkAddresses().addresses().isNotEmpty())
        val advertiser = JmDnsAdvertiser()
        val browser = JmDnsBrowser(excludeOwn = false)
        try {
            browser.start()
            advertiser.register("Test host", 40123, pinRequired = false, id = "host-id")
            val found = withTimeout(30.seconds) { browser.hosts.first { hosts -> hosts.any { it.name == "Test host" } } }
            val host = found.single { it.name == "Test host" }
            assertEquals(40123, host.port)
            assertEquals(false, host.pinRequired)
            assertEquals("host-id", host.id)
        } finally {
            advertiser.unregister()
            browser.stop()
        }
    }
}
