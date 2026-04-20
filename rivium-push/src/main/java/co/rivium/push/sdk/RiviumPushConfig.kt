package co.rivium.push.sdk

/**
 * Configuration for Rivium Push SDK
 *
 * Only apiKey is required. Push server configuration is automatically
 * fetched from the server during initialization.
 */
data class RiviumPushConfig(
    /** Your Rivium Push API key (required) */
    val apiKey: String,

    /** Android notification icon resource name (e.g., "ic_notification") */
    val notificationIcon: String? = null,

    /**
     * Show persistent "Push notifications active" notification on Android (default: true)
     * Set to false to hide the foreground service notification.
     * Note: The service still runs in foreground mode for reliability,
     * but the notification will be minimized/hidden.
     */
    val showServiceNotification: Boolean = true,

    /**
     * Show notifications when app is in foreground (default: true)
     * When false, notifications will only be displayed when the app is in background/killed.
     * The onMessage callback will still be invoked for Flutter to handle foreground messages.
     */
    val showNotificationInForeground: Boolean = true,

    // Internal push server configuration - fetched from server
    internal var pushHost: String = "",
    internal var pushPort: Int = 8883,
    internal var pushSecure: Boolean = true,  // Enable TLS/SSL by default
    internal var pushUsername: String? = null,
    internal var pushPassword: String? = null,
    // PN Protocol token for authentication
    internal var pnToken: String? = null
) {
    // Internal notification channel config - not user-configurable
    internal val notificationChannelId: String = "rivium_push_channel"
    internal val notificationChannelName: String = "Push Notifications"

    // Aliases for PN Protocol naming convention
    internal val pnHost: String get() = pushHost
    internal val pnPort: Int get() = pushPort
    internal val pnSecure: Boolean get() = pushSecure

    companion object {
        /** Production API URL */
        private const val PRODUCTION_API_URL = "https://push-api.rivium.co"

        /** API URL — production by default; overridden by DEV_SERVER_URL in local.properties */
        internal var SERVER_URL: String = BuildConfig.DEV_SERVER_URL
            .takeIf { it.isNotEmpty() } ?: PRODUCTION_API_URL

        /** Push server port (TLS) */
        internal var PUSH_PORT: Int = 8883

        /** Reset to production defaults (used by tests) */
        internal fun resetDefaults() {
            SERVER_URL = PRODUCTION_API_URL
            PUSH_PORT = 8883
        }
    }

    /** Check if push config has been fetched from server */
    internal fun hasPushConfig(): Boolean = pushHost.isNotEmpty()

    /** Update push server configuration from server response */
    internal fun updatePushConfig(host: String, port: Int = 8883, secure: Boolean = true, token: String? = null, username: String? = null, password: String? = null) {
        pushHost = host
        pushPort = port
        pushSecure = secure
        pnToken = token
        pushUsername = username
        pushPassword = password
    }
}
