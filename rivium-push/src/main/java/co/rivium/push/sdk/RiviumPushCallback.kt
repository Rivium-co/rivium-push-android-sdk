package co.rivium.push.sdk

/**
 * Callback interface for Rivium Push events.
 * Implement this interface to receive push notifications and SDK state changes.
 */
interface RiviumPushCallback {
    /**
     * Called when a push message is received
     */
    fun onMessageReceived(message: RiviumPushMessage)

    /**
     * Called when connection state changes
     */
    fun onConnectionStateChanged(connected: Boolean)

    /**
     * Called when device is registered successfully
     */
    fun onRegistered(deviceId: String)

    /**
     * Called when an error occurs (simple string message)
     */
    fun onError(error: String)

    /**
     * Called when a detailed error occurs with error codes.
     * Override this for more structured error handling.
     */
    fun onDetailedError(error: RiviumPushError) {
        // Default implementation delegates to onError
        onError(error.message)
    }

    /**
     * Called when the SDK is automatically retrying connection with exponential backoff.
     * @param attempt Current retry attempt number (0-based)
     * @param nextRetryMs Time in milliseconds until next retry
     */
    fun onReconnecting(attempt: Int, nextRetryMs: Long) {
        // Default: no-op
    }

    /**
     * Called when network connectivity changes.
     * The SDK automatically reconnects pn-protocol when network becomes available.
     * @param isAvailable Whether network is available
     * @param networkType The type of network (wifi, cellular, ethernet, vpn, none, unknown)
     */
    fun onNetworkStateChanged(isAvailable: Boolean, networkType: String) {
        // Default: no-op
    }

    /**
     * Called when the app transitions between foreground and background.
     * @param isInForeground Whether app is in foreground
     */
    fun onAppStateChanged(isInForeground: Boolean) {
        // Default: no-op
    }

    /**
     * Called when the SDK detects the app was updated.
     * When needsReregistration is true, call register() to refresh the device token.
     * @param previousVersion The previous app version before the update
     * @param currentVersion The current app version after the update
     * @param needsReregistration Whether re-registration is recommended
     */
    fun onAppUpdated(previousVersion: String, currentVersion: String, needsReregistration: Boolean) {
        // Default: no-op
    }

    /**
     * Called when the user taps on a notification.
     * This is called in addition to storing the message for getInitialMessage().
     * Use this to handle notification taps when the app is already in foreground.
     * @param message The notification message that was tapped
     */
    fun onNotificationTapped(message: RiviumPushMessage) {
        // Default: no-op
    }
}

/**
 * Simple callback adapter with default empty implementations.
 * Extend this class to only override the callbacks you need.
 */
open class RiviumPushCallbackAdapter : RiviumPushCallback {
    override fun onMessageReceived(message: RiviumPushMessage) {}
    override fun onConnectionStateChanged(connected: Boolean) {}
    override fun onRegistered(deviceId: String) {}
    override fun onError(error: String) {}
    override fun onDetailedError(error: RiviumPushError) {
        onError(error.message)
    }
    override fun onReconnecting(attempt: Int, nextRetryMs: Long) {}
    override fun onNetworkStateChanged(isAvailable: Boolean, networkType: String) {}
    override fun onAppStateChanged(isInForeground: Boolean) {}
    override fun onAppUpdated(previousVersion: String, currentVersion: String, needsReregistration: Boolean) {}
    override fun onNotificationTapped(message: RiviumPushMessage) {}
}
