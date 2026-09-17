package co.rivium.push.sdk

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Marks APIs that exist only for official Rivium Push add-ons (such as
 * `co.rivium:rivium-push-fcm`). They are not part of the supported public API
 * and may change in any release. Apps should not call them.
 */
@RequiresOptIn(
    message = "Internal Rivium Push API for official add-ons. It may change without notice.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class RiviumPushInternalApi

/**
 * An additional delivery transport (e.g. FCM) contributed by an add-on.
 * PN Protocol stays the primary transport; an extra transport only delivers
 * copies of the same messages, which the SDK deduplicates by messageId.
 */
@RiviumPushInternalApi
interface PushTransport {
    /** Transport name reported with delivery receipts, e.g. "fcm". */
    val name: String

    /**
     * Add this transport's fields to the device register request. Called on
     * every registration, possibly on the main thread — return cached values,
     * never block. A key mapped to null is sent as JSON null (clears it on the
     * server); leave a key out to keep the server's current value.
     */
    fun onRegister(context: Context, fields: MutableMap<String, Any?>)
}

/**
 * Entry point for add-on transports. Reach it through [RiviumPush.internalTransports].
 */
@RiviumPushInternalApi
object RiviumPushTransports {
    private const val TAG = "Transports"

    /** Transport name for messages received over PN Protocol. */
    const val TRANSPORT_PN = "pn"

    private val transports = CopyOnWriteArrayList<PushTransport>()

    /** Register an add-on transport. Registering the same name twice replaces it. */
    fun register(transport: PushTransport) {
        transports.removeAll { it.name == transport.name }
        transports.add(transport)
        Log.d(TAG, "Transport registered: ${transport.name}")
    }

    /**
     * Run an incoming message payload (the same JSON string PN Protocol delivers)
     * through the normal pipeline: dedupe, delivery receipt, notification,
     * integrations broadcast and callback. Works when the push service is not
     * running (process started by the transport); configuration is then restored
     * from what RiviumPush.init() persisted. Never throws.
     *
     * @return false if the SDK was never configured on this install.
     */
    fun handlePayload(context: Context, payload: String, transport: String): Boolean {
        return try {
            RiviumPushService.handleIncomingPayload(context, payload, transport)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle $transport payload: ${e.message}")
            false
        }
    }

    /**
     * A transport's registration fields changed (e.g. a new FCM token). Re-registers
     * the device silently if it registered before and the fields differ from the
     * last successful registration. If RiviumPush.init() has not run yet in this
     * process, the refresh happens shortly after it does. Never throws.
     */
    fun onTransportChanged(context: Context) {
        try {
            RiviumPush.refreshTransportRegistration(context.applicationContext ?: context)
        } catch (e: Exception) {
            Log.w(TAG, "Transport refresh skipped: ${e.message}")
        }
    }

    /** Fields contributed by all registered transports. Never throws. */
    internal fun collectRegisterFields(context: Context): Map<String, Any?> {
        val fields = LinkedHashMap<String, Any?>()
        for (transport in transports) {
            try {
                transport.onRegister(context, fields)
            } catch (e: Exception) {
                Log.w(TAG, "Transport ${transport.name} failed to add register fields: ${e.message}")
            }
        }
        return fields
    }
}
