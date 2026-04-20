package co.rivium.push.sdk

/**
 * Analytics event types for tracking SDK usage.
 * Use with setAnalyticsHandler to track SDK events.
 */
enum class RiviumPushAnalyticsEvent {
    /** SDK was initialized */
    SDK_INITIALIZED,
    /** Device was registered */
    DEVICE_REGISTERED,
    /** Device was unregistered */
    DEVICE_UNREGISTERED,
    /** Push message was received */
    MESSAGE_RECEIVED,
    /** Push message was displayed as notification */
    MESSAGE_DISPLAYED,
    /** Notification was clicked */
    NOTIFICATION_CLICKED,
    /** Action button was clicked */
    ACTION_CLICKED,
    /** pn-protocol connected successfully */
    CONNECTED,
    /** pn-protocol disconnected */
    DISCONNECTED,
    /** Connection error occurred */
    CONNECTION_ERROR,
    /** Retry attempt started (during exponential backoff) */
    RETRY_STARTED,
    /** Topic subscribed */
    TOPIC_SUBSCRIBED,
    /** Topic unsubscribed */
    TOPIC_UNSUBSCRIBED,
    /** Network state changed */
    NETWORK_STATE_CHANGED,
    /** App state changed (foreground/background) */
    APP_STATE_CHANGED,
    /** Permission requested */
    PERMISSION_REQUESTED,
    /** Permission granted */
    PERMISSION_GRANTED,
    /** Permission denied */
    PERMISSION_DENIED
}

/**
 * Callback interface for analytics events.
 */
fun interface RiviumPushAnalyticsCallback {
    /**
     * Called when an analytics event occurs.
     * @param event The event type
     * @param properties Additional properties for the event
     */
    fun onEvent(event: RiviumPushAnalyticsEvent, properties: Map<String, Any?>)
}

/**
 * Analytics manager for Rivium Push SDK.
 * Tracks SDK events and reports them to a custom handler.
 */
object RiviumPushAnalytics {
    private const val TAG = "Analytics"

    private var callback: RiviumPushAnalyticsCallback? = null
    private var enabled: Boolean = false

    /**
     * Set the analytics handler to receive SDK events.
     * Automatically enables analytics tracking.
     *
     * @param handler The callback to receive analytics events
     */
    fun setHandler(handler: RiviumPushAnalyticsCallback?) {
        callback = handler
        enabled = handler != null
        Log.d(TAG, "Analytics handler ${if (enabled) "set" else "cleared"}")
    }

    /**
     * Enable analytics tracking.
     * Note: You must also set a handler with setHandler() to receive events.
     */
    fun enable() {
        enabled = true
        Log.d(TAG, "Analytics enabled")
    }

    /**
     * Disable analytics tracking.
     * Events will not be sent to the handler while disabled.
     */
    fun disable() {
        enabled = false
        Log.d(TAG, "Analytics disabled")
    }

    /**
     * Check if analytics is currently enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Track an analytics event.
     * @param event The event type
     * @param properties Additional properties for the event
     */
    internal fun track(event: RiviumPushAnalyticsEvent, properties: Map<String, Any?> = emptyMap()) {
        if (!enabled || callback == null) {
            Log.v(TAG, "Analytics event skipped (disabled): $event")
            return
        }

        try {
            callback?.onEvent(event, properties)
            Log.v(TAG, "Analytics event tracked: $event")
        } catch (e: Exception) {
            Log.e(TAG, "Analytics callback error: ${e.message}")
        }
    }

    /**
     * Track an analytics event with a single property.
     */
    internal fun track(event: RiviumPushAnalyticsEvent, key: String, value: Any?) {
        track(event, mapOf(key to value))
    }
}
