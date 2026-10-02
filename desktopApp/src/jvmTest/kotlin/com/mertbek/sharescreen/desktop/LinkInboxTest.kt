package com.mertbek.sharescreen.desktop

import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkInboxTest {
    private val directory = Files.createTempDirectory("link-inbox")
    private val received = LinkedBlockingQueue<String>()

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a later launch hands its link to the first instance`() {
        LinkInbox.open(directory)!!.use { inbox ->
            inbox.listen(received::add)
            val link = "sharescreen://join?s=share.example.com&r=ABC234&pin=123456&n=Ay%C5%9Fe"

            assertTrue(LinkInbox.forward(directory, link))
            assertEquals(link, received.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `only one instance holds the inbox at a time`() {
        val first = assertNotNull(LinkInbox.open(directory))
        assertNull(LinkInbox.open(directory))
        first.close()
        LinkInbox.open(directory)!!.close()
    }

    @Test
    fun `messages without the token are ignored`() {
        LinkInbox.open(directory)!!.use { inbox ->
            inbox.listen(received::add)
            val port = directory.resolve("instance.port").readText().substringBefore(' ').toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write("wrong sharescreen://join?s=x.test&r=ABC234&pin=123456\n".encodeToByteArray())
                socket.shutdownOutput()
            }

            assertTrue(LinkInbox.forward(directory, "sharescreen://join?s=x.test&r=XYZ789&pin=654321"))
            assertEquals("sharescreen://join?s=x.test&r=XYZ789&pin=654321", received.poll(5, TimeUnit.SECONDS))
            assertNull(received.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `forwarding fails when no instance is running`() {
        assertFalse(LinkInbox.forward(directory, "sharescreen://join?s=x.test&r=ABC234&pin=123456"))
    }
}
