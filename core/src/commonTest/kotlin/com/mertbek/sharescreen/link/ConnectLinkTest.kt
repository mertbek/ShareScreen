package com.mertbek.sharescreen.link

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ConnectLinkTest {

    @Test
    fun `lan links round-trip including a name with spaces and non-ASCII characters`() {
        val link = ConnectLink.Lan(host = "192.168.1.250", port = 41851, pin = "090416", name = "Mert'in Galaxy S23 & Ş")
        assertEquals(link, ConnectLink.parse(link.toUri()))
    }

    @Test
    fun `lan link format is unchanged`() {
        val link = ConnectLink.Lan(host = "10.0.0.5", port = 8080, pin = "123456")
        assertEquals("sharescreen://connect?h=10.0.0.5&p=8080&pin=123456", link.toUri())
        assertEquals(link, ConnectLink.parse(link.toUri()))
    }

    @Test
    fun `internet links round-trip`() {
        val link = ConnectLink.Internet(server = "wss://share.example.com:8443", roomCode = "ABC234", pin = "000001", name = "Tablet")
        assertEquals(link, ConnectLink.parse(link.toUri()))
    }

    @Test
    fun `internet links normalize the server and room code`() {
        assertEquals(
            ConnectLink.Internet("wss://share.example.com", "ABC234", "123456"),
            ConnectLink.parse("sharescreen://join?s=share.example.com&r=abc234&pin=123456"),
        )
    }

    @Test
    fun `parameter order does not matter and the scheme is case-insensitive`() {
        assertEquals(
            ConnectLink.Lan("192.168.0.2", 5000, "000001"),
            ConnectLink.parse("ShareScreen://connect?pin=000001&p=5000&h=192.168.0.2"),
        )
    }

    @Test
    fun `rejects other schemes, hosts and malformed values`() {
        val invalid = listOf(
            "https://connect?h=192.168.1.2&p=5000&pin=123456",
            "sharescreen://other?h=192.168.1.2&p=5000&pin=123456",
            "sharescreen://connect?h=192.168.1.300&p=5000&pin=123456",
            "sharescreen://connect?h=example.com&p=5000&pin=123456",
            "sharescreen://connect?h=192.168.1.2&p=70000&pin=123456",
            "sharescreen://connect?h=192.168.1.2&p=5000&pin=12345",
            "sharescreen://connect?h=192.168.1.2&p=5000&pin=12a456",
            "sharescreen://connect?h=192.168.1.2&p=5000",
            "sharescreen://join?s=ftp%3A%2F%2Fx.test&r=ABC234&pin=123456",
            "sharescreen://join?s=x.test&r=ABC&pin=123456",
            "sharescreen://join?r=ABC234&pin=123456",
            "not a uri at all %%%",
            "",
        )
        for (value in invalid) assertNull(ConnectLink.parse(value), value)
    }

    @Test
    fun `internet invites round-trip as web addresses with everything after the hash`() {
        val link = ConnectLink.Internet("wss://share.example.com", "ABC234", "123456", "Ayşe's phone")
        val web = link.toWebUri()!!

        assertTrue(web.startsWith("https://share.example.com/join#r=ABC234&p=123456&n="), web)
        assertFalse(web.substringBefore('#').contains("123456"))
        assertEquals(link, ConnectLink.parse(web))
    }

    @Test
    fun `web invites keep a custom port and match the room code case-insensitively`() {
        assertEquals(
            ConnectLink.Internet("wss://share.example.com:8443", "ABC234", "123456"),
            ConnectLink.parse("https://share.example.com:8443/join#r=abc234&p=123456"),
        )
    }

    @Test
    fun `only servers reached over TLS have web invites`() {
        assertNull(ConnectLink.Internet("ws://10.0.2.2:8080", "ABC234", "123456").toWebUri())
    }

    @Test
    fun `rejects web addresses that are not invites`() {
        val invalid = listOf(
            "https://share.example.com/",
            "https://share.example.com/privacy#r=ABC234&p=123456",
            "https://share.example.com/join",
            "https://share.example.com/join#r=ABC234",
            "https://share.example.com/join#r=ABC&p=123456",
            "https://share.example.com/join?r=ABC234&p=123456",
        )
        for (value in invalid) assertNull(ConnectLink.parse(value), value)
    }

    @Test
    fun `validates IPv4 addresses`() {
        assertTrue(isIpv4Address("192.168.1.1"))
        assertTrue(isIpv4Address("0.0.0.0"))
        assertFalse(isIpv4Address("192.168.1"))
        assertFalse(isIpv4Address("192.168.1.256"))
        assertFalse(isIpv4Address("192.168..1"))
        assertFalse(isIpv4Address("1.2.3.4.5"))
        assertFalse(isIpv4Address("a.b.c.d"))
    }
}
