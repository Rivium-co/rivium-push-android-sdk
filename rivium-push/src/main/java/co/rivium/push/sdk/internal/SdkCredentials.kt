package co.rivium.push.sdk.internal

/**
 * SDK public configuration - these are the production Rivium Push server endpoints.
 * These values are public and shipped with the SDK.
 */
internal object SdkCredentials {
    /** Production API URL - public endpoint for SDK users */
    const val API_URL = "https://push-api.rivium.co"

    /** Production PN Protocol gateway host - public endpoint for real-time messaging */
    const val PN_HOST = "pn-tcp.rivium.co"

    /** Production PN Protocol gateway port (TLS) */
    const val PN_PORT = 8883

    /** Enable TLS/SSL by default for secure connections */
    const val PN_SECURE = true
}
