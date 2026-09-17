package co.rivium.push.fcm

import android.content.Context
import co.rivium.push.sdk.PushTransport

/**
 * Persists the current FCM token and contributes it to Rivium Push device
 * registration. Registered with the core SDK as the "fcm" transport.
 */
internal object FcmTokenStore : PushTransport {
    private const val PREFS_NAME = "rivium_push_fcm_prefs"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_PROJECT_ID = "fcm_project_id"

    override val name: String = RiviumPushFcm.TRANSPORT

    override fun onRegister(context: Context, fields: MutableMap<String, Any?>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FcmPayload.registerFields(
            prefs.getString(KEY_TOKEN, null),
            prefs.getString(KEY_PROJECT_ID, null),
            fields
        )
    }

    /** Store [token]; returns true if it differs from the stored one. */
    fun save(context: Context, token: String, projectId: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TOKEN, null) == token &&
            prefs.getString(KEY_PROJECT_ID, null) == projectId
        ) return false
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_PROJECT_ID, projectId)
            .apply()
        return true
    }
}
