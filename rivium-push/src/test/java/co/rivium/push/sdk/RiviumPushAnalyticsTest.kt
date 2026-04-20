package co.rivium.push.sdk

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RiviumPushAnalytics.
 * Tests analytics event tracking, enable/disable functionality, and callback handling.
 */
class RiviumPushAnalyticsTest {

    private var receivedEvents: MutableList<Pair<RiviumPushAnalyticsEvent, Map<String, Any?>>> = mutableListOf()

    @Before
    fun setUp() {
        // Mock the Log object to avoid Android runtime dependency
        mockkObject(Log)
        every { Log.d(any(), any()) } returns Unit
        every { Log.v(any(), any()) } returns Unit
        every { Log.e(any(), any(), any()) } returns Unit

        // Reset analytics state before each test
        RiviumPushAnalytics.disable()
        RiviumPushAnalytics.setHandler(null)
        receivedEvents.clear()
    }

    @After
    fun tearDown() {
        // Clean up after each test
        RiviumPushAnalytics.disable()
        RiviumPushAnalytics.setHandler(null)
        unmockkObject(Log)
    }

    // ==================== Enable/Disable Tests ====================

    @Test
    fun `analytics is disabled by default`() {
        assertFalse(RiviumPushAnalytics.isEnabled())
    }

    @Test
    fun `enable enables analytics tracking`() {
        // When
        RiviumPushAnalytics.enable()

        // Then
        assertTrue(RiviumPushAnalytics.isEnabled())
    }

    @Test
    fun `disable disables analytics tracking`() {
        // Given
        RiviumPushAnalytics.enable()

        // When
        RiviumPushAnalytics.disable()

        // Then
        assertFalse(RiviumPushAnalytics.isEnabled())
    }

    @Test
    fun `setHandler automatically enables analytics`() {
        // When
        RiviumPushAnalytics.setHandler { _, _ -> }

        // Then
        assertTrue(RiviumPushAnalytics.isEnabled())
    }

    @Test
    fun `setHandler with null disables analytics`() {
        // Given
        RiviumPushAnalytics.setHandler { _, _ -> }
        assertTrue(RiviumPushAnalytics.isEnabled())

        // When
        RiviumPushAnalytics.setHandler(null)

        // Then
        assertFalse(RiviumPushAnalytics.isEnabled())
    }

    // ==================== Event Tracking Tests ====================

    @Test
    fun `track does not call handler when disabled`() {
        // Given
        var handlerCalled = false
        RiviumPushAnalytics.setHandler { _, _ -> handlerCalled = true }
        RiviumPushAnalytics.disable()

        // When
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.SDK_INITIALIZED)

        // Then
        assertFalse(handlerCalled)
    }

    @Test
    fun `track calls handler when enabled`() {
        // Given
        RiviumPushAnalytics.setHandler { event, properties ->
            receivedEvents.add(Pair(event, properties))
        }

        // When
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.SDK_INITIALIZED)

        // Then
        assertEquals(1, receivedEvents.size)
        assertEquals(RiviumPushAnalyticsEvent.SDK_INITIALIZED, receivedEvents[0].first)
    }

    @Test
    fun `track passes properties correctly`() {
        // Given
        RiviumPushAnalytics.setHandler { event, properties ->
            receivedEvents.add(Pair(event, properties))
        }

        // When
        RiviumPushAnalytics.track(
            RiviumPushAnalyticsEvent.MESSAGE_RECEIVED,
            mapOf("title" to "Test Message", "silent" to false)
        )

        // Then
        assertEquals(1, receivedEvents.size)
        val props = receivedEvents[0].second
        assertEquals("Test Message", props["title"])
        assertEquals(false, props["silent"])
    }

    @Test
    fun `track with single property works correctly`() {
        // Given
        RiviumPushAnalytics.setHandler { event, properties ->
            receivedEvents.add(Pair(event, properties))
        }

        // When
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.DEVICE_REGISTERED, "device_id", "abc123")

        // Then
        assertEquals(1, receivedEvents.size)
        assertEquals("abc123", receivedEvents[0].second["device_id"])
    }

    @Test
    fun `track with empty properties passes empty map`() {
        // Given
        RiviumPushAnalytics.setHandler { event, properties ->
            receivedEvents.add(Pair(event, properties))
        }

        // When
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.CONNECTED)

        // Then
        assertEquals(1, receivedEvents.size)
        assertTrue(receivedEvents[0].second.isEmpty())
    }

    // ==================== Multiple Events Test ====================

    @Test
    fun `multiple events are tracked in order`() {
        // Given
        RiviumPushAnalytics.setHandler { event, properties ->
            receivedEvents.add(Pair(event, properties))
        }

        // When
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.SDK_INITIALIZED)
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.DEVICE_REGISTERED, "device_id", "123")
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.CONNECTED)
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.TOPIC_SUBSCRIBED, "topic", "news")

        // Then
        assertEquals(4, receivedEvents.size)
        assertEquals(RiviumPushAnalyticsEvent.SDK_INITIALIZED, receivedEvents[0].first)
        assertEquals(RiviumPushAnalyticsEvent.DEVICE_REGISTERED, receivedEvents[1].first)
        assertEquals(RiviumPushAnalyticsEvent.CONNECTED, receivedEvents[2].first)
        assertEquals(RiviumPushAnalyticsEvent.TOPIC_SUBSCRIBED, receivedEvents[3].first)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `track handles callback exception gracefully`() {
        // Given
        RiviumPushAnalytics.setHandler { _, _ ->
            throw RuntimeException("Test exception")
        }

        // When/Then - should not throw
        RiviumPushAnalytics.track(RiviumPushAnalyticsEvent.SDK_INITIALIZED)
    }

    // ==================== Event Types Test ====================

    @Test
    fun `all analytics event types are defined`() {
        // Verify we have all expected event types
        val events = RiviumPushAnalyticsEvent.values()

        assertTrue(events.contains(RiviumPushAnalyticsEvent.SDK_INITIALIZED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.DEVICE_REGISTERED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.DEVICE_UNREGISTERED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.MESSAGE_RECEIVED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.MESSAGE_DISPLAYED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.NOTIFICATION_CLICKED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.ACTION_CLICKED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.CONNECTED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.DISCONNECTED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.CONNECTION_ERROR))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.RETRY_STARTED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.TOPIC_SUBSCRIBED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.TOPIC_UNSUBSCRIBED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.NETWORK_STATE_CHANGED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.APP_STATE_CHANGED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.PERMISSION_REQUESTED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.PERMISSION_GRANTED))
        assertTrue(events.contains(RiviumPushAnalyticsEvent.PERMISSION_DENIED))
    }
}
