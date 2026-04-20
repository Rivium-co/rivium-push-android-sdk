package co.rivium.push.sdk

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for state classes: NetworkType, NetworkState, AppState, ReconnectionState.
 */
class RiviumPushStateTest {

    // ==================== NetworkType Tests ====================

    @Test
    fun `NetworkType fromString returns correct type for wifi`() {
        assertEquals(NetworkType.WIFI, NetworkType.fromString("wifi"))
        assertEquals(NetworkType.WIFI, NetworkType.fromString("WIFI"))
        assertEquals(NetworkType.WIFI, NetworkType.fromString("WiFi"))
    }

    @Test
    fun `NetworkType fromString returns correct type for cellular`() {
        assertEquals(NetworkType.CELLULAR, NetworkType.fromString("cellular"))
        assertEquals(NetworkType.CELLULAR, NetworkType.fromString("CELLULAR"))
    }

    @Test
    fun `NetworkType fromString returns correct type for ethernet`() {
        assertEquals(NetworkType.ETHERNET, NetworkType.fromString("ethernet"))
    }

    @Test
    fun `NetworkType fromString returns correct type for vpn`() {
        assertEquals(NetworkType.VPN, NetworkType.fromString("vpn"))
    }

    @Test
    fun `NetworkType fromString returns correct type for none`() {
        assertEquals(NetworkType.NONE, NetworkType.fromString("none"))
    }

    @Test
    fun `NetworkType fromString returns unknown for invalid input`() {
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromString("invalid"))
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromString(""))
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromString("5g"))
    }

    // ==================== NetworkState Tests ====================

    @Test
    fun `NetworkState data class creates correctly`() {
        val state = NetworkState(
            isAvailable = true,
            networkType = NetworkType.WIFI
        )

        assertTrue(state.isAvailable)
        assertEquals(NetworkType.WIFI, state.networkType)
    }

    @Test
    fun `NetworkState with optional fields`() {
        val state = NetworkState(
            isAvailable = true,
            networkType = NetworkType.CELLULAR,
            effectiveType = "4g",
            downlinkMbps = 50.0
        )

        assertEquals("4g", state.effectiveType)
        assertEquals(50.0, state.downlinkMbps)
    }

    @Test
    fun `NetworkState equality works correctly`() {
        val state1 = NetworkState(isAvailable = true, networkType = NetworkType.WIFI)
        val state2 = NetworkState(isAvailable = true, networkType = NetworkType.WIFI)
        val state3 = NetworkState(isAvailable = false, networkType = NetworkType.WIFI)

        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    @Test
    fun `NetworkState copy works correctly`() {
        val original = NetworkState(isAvailable = true, networkType = NetworkType.WIFI)
        val copy = original.copy(networkType = NetworkType.CELLULAR)

        assertTrue(copy.isAvailable)
        assertEquals(NetworkType.CELLULAR, copy.networkType)
    }

    // ==================== AppState Tests ====================

    @Test
    fun `AppState data class creates correctly`() {
        val state = AppState(isInForeground = true)

        assertTrue(state.isInForeground)
        assertNull(state.currentActivity)
    }

    @Test
    fun `AppState with currentActivity`() {
        val state = AppState(
            isInForeground = true,
            currentActivity = "MainActivity"
        )

        assertTrue(state.isInForeground)
        assertEquals("MainActivity", state.currentActivity)
    }

    @Test
    fun `AppState background state`() {
        val state = AppState(isInForeground = false)

        assertFalse(state.isInForeground)
    }

    @Test
    fun `AppState equality works correctly`() {
        val state1 = AppState(isInForeground = true, currentActivity = "MainActivity")
        val state2 = AppState(isInForeground = true, currentActivity = "MainActivity")
        val state3 = AppState(isInForeground = false, currentActivity = "MainActivity")

        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    // ==================== ReconnectionState Tests ====================

    @Test
    fun `ReconnectionState data class creates correctly`() {
        val state = ReconnectionState(
            retryAttempt = 2,
            nextRetryMs = 5000,
            maxRetryAttempts = 10
        )

        assertEquals(2, state.retryAttempt)
        assertEquals(5000, state.nextRetryMs)
        assertEquals(10, state.maxRetryAttempts)
    }

    @Test
    fun `ReconnectionState first attempt`() {
        val state = ReconnectionState(
            retryAttempt = 0,
            nextRetryMs = 1000,
            maxRetryAttempts = 5
        )

        assertEquals(0, state.retryAttempt)
        assertEquals(1000, state.nextRetryMs)
    }

    @Test
    fun `ReconnectionState equality works correctly`() {
        val state1 = ReconnectionState(retryAttempt = 1, nextRetryMs = 2000, maxRetryAttempts = 5)
        val state2 = ReconnectionState(retryAttempt = 1, nextRetryMs = 2000, maxRetryAttempts = 5)
        val state3 = ReconnectionState(retryAttempt = 2, nextRetryMs = 2000, maxRetryAttempts = 5)

        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    // ==================== Combined Scenarios ====================

    @Test
    fun `network unavailable state`() {
        val state = NetworkState(
            isAvailable = false,
            networkType = NetworkType.NONE
        )

        assertFalse(state.isAvailable)
        assertEquals(NetworkType.NONE, state.networkType)
    }

    @Test
    fun `app in background with wifi`() {
        val networkState = NetworkState(
            isAvailable = true,
            networkType = NetworkType.WIFI
        )
        val appState = AppState(isInForeground = false)

        assertTrue(networkState.isAvailable)
        assertFalse(appState.isInForeground)
    }
}
