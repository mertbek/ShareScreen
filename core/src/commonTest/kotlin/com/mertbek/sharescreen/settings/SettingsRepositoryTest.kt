package com.mertbek.sharescreen.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {

    @Test
    fun `defaults use the built-in server`() {
        val settings = SettingsRepository(MapSettings()).settings.value
        assertEquals(VideoQuality.STANDARD, settings.quality)
        assertEquals(DEFAULT_SERVER, settings.internetServer)
    }

    @Test
    fun `changes are stored and reflected`() {
        val store = MapSettings()
        val repository = SettingsRepository(store)

        repository.setQuality(VideoQuality.HIGH)
        repository.setShareAudio(false)
        repository.setAllowRemoteControl(true)
        repository.setCustomServer("wss://example.com")

        val current = repository.settings.value
        assertEquals(VideoQuality.HIGH, current.quality)
        assertEquals(false, current.shareAudio)
        assertEquals(true, current.allowRemoteControl)
        assertEquals("wss://example.com", current.internetServer)
        assertEquals(current, SettingsRepository(store).settings.value)
    }

    @Test
    fun `turning the internet off removes the server`() {
        val repository = SettingsRepository(MapSettings())
        repository.setInternetEnabled(false)
        assertNull(repository.settings.value.internetServer)
    }

    @Test
    fun `clearing the custom server goes back to the default`() {
        val repository = SettingsRepository(MapSettings())
        repository.setCustomServer("wss://example.com")
        repository.setCustomServer(null)
        assertEquals(DEFAULT_SERVER, repository.settings.value.internetServer)
    }
}
