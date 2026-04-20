package co.rivium.push.sdk

/**
 * Network type enumeration
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    NONE,
    UNKNOWN;

    companion object {
        fun fromString(type: String): NetworkType {
            return when (type.lowercase()) {
                "wifi" -> WIFI
                "cellular" -> CELLULAR
                "ethernet" -> ETHERNET
                "vpn" -> VPN
                "none" -> NONE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Represents the current network state
 */
data class NetworkState(
    /** Whether network is currently available */
    val isAvailable: Boolean,
    /** The type of network connection */
    val networkType: NetworkType,
    /** Effective connection type (4g, 3g, 2g, etc.) - Android only */
    val effectiveType: String? = null,
    /** Downlink speed in Mbps - may be null on older Android versions */
    val downlinkMbps: Double? = null
)

/**
 * Represents the app's foreground/background state
 */
data class AppState(
    /** Whether the app is currently in foreground */
    val isInForeground: Boolean,
    /** Activity class name if in foreground, null otherwise */
    val currentActivity: String? = null
)

/**
 * Represents the reconnection state during automatic retry
 */
data class ReconnectionState(
    /** Current retry attempt number (0-based) */
    val retryAttempt: Int,
    /** Time in milliseconds until next retry */
    val nextRetryMs: Long,
    /** Maximum retry attempts */
    val maxRetryAttempts: Int
)
