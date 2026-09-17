package co.rivium.push.fcm

import android.content.Context
import co.rivium.push.sdk.Log
import co.rivium.push.sdk.RiviumPush
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Optional FCM transport for Rivium Push.
 *
 * Adding `co.rivium:rivium-push-fcm` is enough: the token is fetched on app start
 * and [RiviumFcmMessagingService] receives messages. PN Protocol keeps working as
 * before; a message arriving over both transports is shown once.
 *
 * Apps that already have their own `FirebaseMessagingService` must forward to
 * this object, because Android delivers FCM events to a single service:
 *
 * ```kotlin
 * class MyMessagingService : FirebaseMessagingService() {
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         if (RiviumPushFcm.handleMessage(message)) return // was a Rivium message
 *         // your own handling
 *     }
 *
 *     override fun onNewToken(token: String) {
 *         RiviumPushFcm.onNewToken(token)
 *         // your own handling
 *     }
 * }
 * ```
 *
 * and remove the add-on's service in the app manifest so it does not compete:
 *
 * ```xml
 * <service
 *     android:name="co.rivium.push.fcm.RiviumFcmMessagingService"
 *     tools:node="remove" />
 * ```
 */
object RiviumPushFcm {
    private const val TAG = "Fcm"

    /** Transport name reported with delivery receipts. */
    const val TRANSPORT = "fcm"

    private const val TOKEN_TIMEOUT_SECONDS = 30L

    @Volatile private var appContext: Context? = null

    /**
     * Handle an FCM message. Returns true if it was a Rivium Push message (it is
     * then shown, acked and passed to the Rivium callback), false otherwise.
     * Never throws.
     */
    @JvmStatic
    fun handleMessage(message: RemoteMessage): Boolean {
        val ctx = context() ?: return FcmPayload.extract(message.data) != null
        return handleMessage(ctx, message)
    }

    /** Same as [handleMessage], with an explicit context. */
    @JvmStatic
    fun handleMessage(context: Context, message: RemoteMessage): Boolean {
        val data = try { message.data } catch (e: Exception) { null }
        val payload = FcmPayload.extract(data) ?: return false
        if (FcmPayload.isTruncated(data)) {
            Log.d(TAG, "Message was truncated to fit FCM limits (image/actions dropped)")
        }
        remember(context)
        RiviumPush.internalTransports.handlePayload(context, payload, TRANSPORT)
        return true
    }

    /** Pass a new FCM token to Rivium Push. Never throws. */
    @JvmStatic
    fun onNewToken(token: String) {
        val ctx = context() ?: run {
            Log.d(TAG, "New FCM token ignored: no context yet")
            return
        }
        onNewToken(ctx, token)
    }

    /** Same as [onNewToken], with an explicit context. */
    @JvmStatic
    fun onNewToken(context: Context, token: String) {
        try {
            remember(context)
            val ctx = context.applicationContext ?: context
            if (FcmTokenStore.save(ctx, token, projectId(ctx))) {
                Log.d(TAG, "FCM token updated")
            }
            // Always ask: the core only re-registers if the token differs from the
            // last successful registration, so a failed earlier attempt is retried.
            RiviumPush.internalTransports.onTransportChanged(ctx)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to store FCM token: ${e.message}")
        }
    }

    /**
     * Register the transport and fetch the current token in the background.
     * Called by [RiviumFcmInitializer]. Any failure (no Google Play services,
     * Firebase not configured, blocked network) is logged and ignored; the
     * next app start tries again. PN Protocol is never affected.
     */
    internal fun start(context: Context) {
        remember(context)
        val ctx = context.applicationContext ?: context
        RiviumPush.internalTransports.register(FcmTokenStore)
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                Log.d(TAG, "Firebase not initialized (google-services.json missing?) - FCM disabled")
                return
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Firebase unavailable - FCM disabled: ${e.message}")
            return
        }
        Thread({
            try {
                val token = Tasks.await(
                    FirebaseMessaging.getInstance().token,
                    TOKEN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                if (!token.isNullOrEmpty()) onNewToken(ctx, token)
            } catch (e: Throwable) {
                Log.d(TAG, "FCM token not available, will retry next start: ${e.message}")
            }
        }, "rivium-push-fcm-token").apply { isDaemon = true }.start()
    }

    private fun remember(context: Context) {
        if (appContext == null) appContext = context.applicationContext ?: context
    }

    private fun context(): Context? {
        appContext?.let { return it }
        return try {
            FirebaseApp.getInstance().applicationContext.also { appContext = it }
        } catch (e: Throwable) {
            null
        }
    }

    private fun projectId(context: Context): String? = try {
        FirebaseApp.getApps(context).firstOrNull()?.options?.projectId
    } catch (e: Throwable) {
        null
    }
}
