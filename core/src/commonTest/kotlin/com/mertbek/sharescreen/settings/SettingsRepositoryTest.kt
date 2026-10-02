package com.mertbek.sharescreen.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {

    @Test
    fun `defaults use the configured server`() {
        val settings = SettingsRepository(MapSettings(), DEFAULT).settings.value
        assertEquals(VideoQuality.STANDARD, settings.quality)
        assertEquals(DEFAULT, settings.internetServer)
        assertEquals(false, settings.lanPin)
    }

    @Test
    fun `without a configured server there is none until one is set`() {
        val repository = SettingsRepository(MapSettings())
        assertNull(repository.settings.value.internetServer)
        repository.setCustomServer("wss://example.com")
        assertEquals("wss://example.com", repository.settings.value.internetServer)
    }

    @Test
    fun `changes are stored and reflected`() {
        val store = MapSettings()
        val repository = SettingsRepository(store)

        repository.setQuality(VideoQuality.HIGH)
        repository.setShareAudio(false)
        repository.setAllowRemoteControl(true)
        repository.setLanPin(true)
        repository.setCustomServer("wss://example.com")

        val current = repository.settings.value
        assertEquals(VideoQuality.HIGH, current.quality)
        assertEquals(false, current.shareAudio)
        assertEquals(true, current.allowRemoteControl)
        assertEquals(true, current.lanPin)
        assertEquals("wss://example.com", current.internetServer)
        assertEquals(current, SettingsRepository(store).settings.value)
    }

    @Test
    fun `turning the internet off removes the server`() {
        val repository = SettingsRepository(MapSettings(), DEFAULT)
        repository.setInternetEnabled(false)
        assertNull(repository.settings.value.internetServer)
    }

    @Test
    fun `clearing the custom server goes back to the default`() {
        val repository = SettingsRepository(MapSettings(), DEFAULT)
        repository.setCustomServer("wss://example.com")
        repository.setCustomServer(null)
        assertEquals(DEFAULT, repository.settings.value.internetServer)
    }

    private companion object {
        const val DEFAULT = "wss://default.example"
    }
}
