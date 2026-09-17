package co.rivium.push.fcm

/**
 * Pure helpers for the FCM data message format. Kept free of Firebase and
 * Android types so they can be unit tested on the JVM.
 */
internal object FcmPayload {
    /** Data key holding the PushMessage JSON (same string as over PN Protocol). */
    const val KEY_RIVIUM = "rivium"

    /** "1" when the server dropped imageUrl/actions to fit FCM's 4KB limit. */
    const val KEY_TRUNCATED = "truncated"

    const val FIELD_TOKEN = "fcmToken"
    const val FIELD_PROJECT_ID = "fcmProjectId"

    /** The Rivium payload of a data message, or null if it is not a Rivium message. */
    fun extract(data: Map<String, String>?): String? =
        data?.get(KEY_RIVIUM)?.takeIf { it.isNotBlank() }

    fun isTruncated(data: Map<String, String>?): Boolean = data?.get(KEY_TRUNCATED) == "1"

    /**
     * Register fields for a known token. Nothing is added without a token, so a
     * transient Firebase failure never clears the token stored on the server.
     */
    fun registerFields(token: String?, projectId: String?, fields: MutableMap<String, Any?>) {
        if (token.isNullOrEmpty()) return
        fields[FIELD_TOKEN] = token
        if (!projectId.isNullOrEmpty()) fields[FIELD_PROJECT_ID] = projectId
    }
}
