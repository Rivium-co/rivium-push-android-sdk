package co.rivium.push.fcm

import android.content.Context
import androidx.startup.Initializer

/**
 * Starts the FCM add-on on app start, so no app code is needed. Only registers
 * the transport and fetches the token in the background; never blocks startup.
 */
class RiviumFcmInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        try {
            RiviumPushFcm.start(context)
        } catch (e: Throwable) {
            co.rivium.push.sdk.Log.d("Fcm", "FCM add-on not started: ${e.message}")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
