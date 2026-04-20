package co.rivium.push.sdk

/**
 * Standardized error codes for Rivium Push SDK.
 * These codes help developers identify and handle specific error scenarios.
 */
enum class RiviumPushErrorCode(val code: Int, val message: String) {
    // Connection errors (1000-1099)
    CONNECTION_FAILED(1000, "Failed to connect to PN Protocol gateway"),
    CONNECTION_TIMEOUT(1001, "Connection timed out"),
    CONNECTION_LOST(1002, "Connection to server was lost"),
    CONNECTION_REFUSED(1003, "Connection was refused by server"),
    AUTHENTICATION_FAILED(1004, "Authentication failed - invalid credentials"),
    SSL_ERROR(1005, "SSL/TLS handshake failed"),
    BROKER_UNAVAILABLE(1006, "PN Protocol gateway is unavailable"),

    // Subscription errors (1100-1199)
    SUBSCRIPTION_FAILED(1100, "Failed to subscribe to topic"),
    UNSUBSCRIPTION_FAILED(1101, "Failed to unsubscribe from topic"),
    INVALID_TOPIC(1102, "Invalid topic format"),

    // Message errors (1200-1299)
    MESSAGE_DELIVERY_FAILED(1200, "Failed to deliver message"),
    MESSAGE_PARSE_ERROR(1201, "Failed to parse message payload"),
    MESSAGE_TIMEOUT(1202, "Message delivery timed out"),

    // Configuration errors (1300-1399)
    INVALID_CONFIG(1300, "Invalid configuration"),
    MISSING_API_KEY(1301, "API key is missing"),
    MISSING_SERVER_URL(1302, "Server URL is missing"),
    INVALID_CREDENTIALS(1303, "Invalid PN Protocol credentials"),
    CONFIGURATION_FAILED(1304, "Failed to fetch configuration from server"),

    // Registration errors (1400-1499)
    REGISTRATION_FAILED(1400, "Device registration failed"),
    DEVICE_ID_GENERATION_FAILED(1401, "Failed to generate device ID"),
    SERVER_ERROR(1402, "Server returned an error"),
    NETWORK_ERROR(1403, "Network request failed"),

    // State errors (1500-1599)
    NOT_INITIALIZED(1500, "SDK is not initialized"),
    NOT_CONNECTED(1501, "Not connected to server"),
    ALREADY_CONNECTED(1502, "Already connected to server"),
    SERVICE_NOT_RUNNING(1503, "Background service is not running"),

    // Unknown error
    UNKNOWN_ERROR(9999, "An unknown error occurred");

    companion object {
        fun fromCode(code: Int): RiviumPushErrorCode {
            return values().find { it.code == code } ?: UNKNOWN_ERROR
        }

        fun fromPNException(e: Exception): RiviumPushErrorCode {
            return when {
                e.message?.contains("Connection refused", ignoreCase = true) == true -> CONNECTION_REFUSED
                e.message?.contains("Connection lost", ignoreCase = true) == true -> CONNECTION_LOST
                e.message?.contains("Timed out", ignoreCase = true) == true -> CONNECTION_TIMEOUT
                e.message?.contains("timeout", ignoreCase = true) == true -> CONNECTION_TIMEOUT
                e.message?.contains("Not authorized", ignoreCase = true) == true -> AUTHENTICATION_FAILED
                e.message?.contains("Bad user name or password", ignoreCase = true) == true -> AUTHENTICATION_FAILED
                e.message?.contains("SSL", ignoreCase = true) == true -> SSL_ERROR
                e.message?.contains("TLS", ignoreCase = true) == true -> SSL_ERROR
                e.message?.contains("Unable to connect", ignoreCase = true) == true -> BROKER_UNAVAILABLE
                e.message?.contains("Server unavailable", ignoreCase = true) == true -> BROKER_UNAVAILABLE
                else -> CONNECTION_FAILED
            }
        }
    }
}

/**
 * Represents a Rivium Push error with code and additional details.
 */
data class RiviumPushError(
    val errorCode: RiviumPushErrorCode,
    val details: String? = null,
    val cause: Throwable? = null
) {
    val code: Int get() = errorCode.code
    val message: String get() = errorCode.message

    fun toMap(): Map<String, Any?> = mapOf(
        "code" to code,
        "message" to message,
        "details" to (details ?: cause?.message),
        "errorName" to errorCode.name
    )

    override fun toString(): String {
        return "RiviumPushError(code=$code, message=$message, details=${details ?: cause?.message})"
    }

    companion object {
        fun fromException(e: Exception, details: String? = null): RiviumPushError {
            return RiviumPushError(
                errorCode = RiviumPushErrorCode.fromPNException(e),
                details = details,
                cause = e
            )
        }
    }
}
