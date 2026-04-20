package co.rivium.push.sdk

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RiviumPushConfig.
 * Tests configuration management and push server settings.
 */
class RiviumPushConfigTest {

    @Before
    fun setUp() {
        // Ensure clean state before each test
        RiviumPushConfig.resetDefaults()
    }

    @After
    fun tearDown() {
        // Clean up after each test
        RiviumPushConfig.resetDefaults()
    }

    // ==================== Basic Configuration Tests ====================

    @Test
    fun `config with only apiKey uses defaults`() {
        // When
        val config = RiviumPushConfig(apiKey = "test_key")

        // Then
        assertEquals("test_key", config.apiKey)
        assertNull(config.notificationIcon)
        assertEquals("rivium_push_channel", config.notificationChannelId)
        assertEquals("Push Notifications", config.notificationChannelName)
        assertTrue(config.showServiceNotification)
    }

    @Test
    fun `config with custom values preserves them`() {
        // When
        val config = RiviumPushConfig(
            apiKey = "my_api_key",
            notificationIcon = "ic_custom_notification",
            showServiceNotification = false
        )

        // Then
        assertEquals("my_api_key", config.apiKey)
        assertEquals("ic_custom_notification", config.notificationIcon)
        assertFalse(config.showServiceNotification)
    }

    // ==================== Push Config Tests ====================

    @Test
    fun `hasPushConfig returns false initially`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // Then
        assertFalse(config.hasPushConfig())
    }

    @Test
    fun `hasPushConfig returns true after updatePushConfig`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // When
        config.updatePushConfig(
            host = "mqtt.example.com",
            port = 1883,
            username = "user",
            password = "pass"
        )

        // Then
        assertTrue(config.hasPushConfig())
    }

    @Test
    fun `updatePushConfig sets all values correctly`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // When
        config.updatePushConfig(
            host = "mqtt.rivium.co",
            port = 8883,
            username = "device123",
            password = "secret_pass"
        )

        // Then
        assertEquals("mqtt.rivium.co", config.pushHost)
        assertEquals(8883, config.pushPort)
        assertEquals("device123", config.pushUsername)
        assertEquals("secret_pass", config.pushPassword)
    }

    @Test
    fun `updatePushConfig uses default port when not specified`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // When
        config.updatePushConfig(host = "mqtt.example.com")

        // Then
        assertEquals("mqtt.example.com", config.pushHost)
        assertEquals(8883, config.pushPort) // default port
    }

    @Test
    fun `updatePushConfig sets TLS configuration`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // When
        config.updatePushConfig(
            host = "secure.rivium.co",
            port = 8883,
            secure = true,
            token = "jwt_token_123"
        )

        // Then
        assertEquals("secure.rivium.co", config.pushHost)
        assertEquals(8883, config.pushPort)
        assertTrue(config.pushSecure)
        assertEquals("jwt_token_123", config.pnToken)
    }

    @Test
    fun `pn aliases return correct values`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")
        config.updatePushConfig(
            host = "pn.example.com",
            port = 443,
            secure = true
        )

        // Then - aliases should match
        assertEquals(config.pushHost, config.pnHost)
        assertEquals(config.pushPort, config.pnPort)
        assertEquals(config.pushSecure, config.pnSecure)
    }

    // ==================== Server URL Tests ====================

    @Test
    fun `SERVER_URL returns production URL by default`() {
        // Then
        assertEquals("https://push-api.rivium.co", RiviumPushConfig.SERVER_URL)
    }

    @Test
    fun `PUSH_PORT returns 8883 by default`() {
        // Then
        assertEquals(8883, RiviumPushConfig.PUSH_PORT)
    }

    @Test
    fun `resetDefaults restores production settings`() {
        // Given
        RiviumPushConfig.SERVER_URL = "http://localhost:3000"
        RiviumPushConfig.PUSH_PORT = 1883

        // When
        RiviumPushConfig.resetDefaults()

        // Then
        assertEquals("https://push-api.rivium.co", RiviumPushConfig.SERVER_URL)
        assertEquals(8883, RiviumPushConfig.PUSH_PORT)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `config with empty apiKey is allowed`() {
        // This might be invalid at runtime, but constructor should allow it
        val config = RiviumPushConfig(apiKey = "")

        assertEquals("", config.apiKey)
    }

    @Test
    fun `multiple updatePushConfig calls overwrite previous values`() {
        // Given
        val config = RiviumPushConfig(apiKey = "test_key")

        // When - first update
        config.updatePushConfig(
            host = "first.example.com",
            port = 1883
        )

        // When - second update
        config.updatePushConfig(
            host = "second.example.com",
            port = 8883,
            username = "user2"
        )

        // Then - only second values should remain
        assertEquals("second.example.com", config.pushHost)
        assertEquals(8883, config.pushPort)
        assertEquals("user2", config.pushUsername)
    }

    @Test
    fun `config data class equality works correctly`() {
        // Given
        val config1 = RiviumPushConfig(apiKey = "key1")
        val config2 = RiviumPushConfig(apiKey = "key1")
        val config3 = RiviumPushConfig(apiKey = "key2")

        // Then
        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
    }

    @Test
    fun `config copy creates independent instance`() {
        // Given
        val original = RiviumPushConfig(apiKey = "original_key")
        original.updatePushConfig(host = "original.host.com")

        // When
        val copy = original.copy(apiKey = "copy_key")
        copy.updatePushConfig(host = "copy.host.com")

        // Then - original should be unchanged
        assertEquals("original_key", original.apiKey)
        assertEquals("original.host.com", original.pushHost)
        assertEquals("copy_key", copy.apiKey)
        assertEquals("copy.host.com", copy.pushHost)
    }
}
