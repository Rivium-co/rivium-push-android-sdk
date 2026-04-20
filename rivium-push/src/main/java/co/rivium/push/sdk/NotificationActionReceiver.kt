package co.rivium.push.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import co.rivium.push.sdk.abtesting.ABTestingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BroadcastReceiver that handles notification action button clicks.
 * Automatically tracks A/B test clicks when notifications contain abTestId/variantId.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ActionReceiver"
        private const val PREFS_NAME = "rivium_push_prefs"
        const val EXTRA_AB_TEST_ID = "abTestId"
        const val EXTRA_VARIANT_ID = "variantId"

        // Lazy HTTP client for direct API calls when SDK not initialized
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Check if the deep link is a valid URI with a scheme (like myapp:// or https://)
     */
    private fun isValidDeepLink(deepLink: String?): Boolean {
        if (deepLink.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(deepLink)
            // Must have a scheme (like myapp://, https://, etc.)
            !uri.scheme.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create the intent to launch when action button is clicked
     */
    private fun createLaunchIntent(
        context: Context,
        deepLink: String?,
        actionId: String?,
        messageId: String?
    ): Intent? {
        return if (isValidDeepLink(deepLink)) {
            // Deep link with valid URI scheme - try to open it
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_ACTION_ID, actionId)
                putExtra(NotificationHelper.EXTRA_MESSAGE_ID, messageId)
            }
        } else {
            // No valid deep link or just a plain value - launch main activity
            // Store the action value for the app to handle
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_ACTION_ID, actionId)
                putExtra(NotificationHelper.EXTRA_MESSAGE_ID, messageId)
                // Pass the action value (could be anything like "125", "reply", etc.)
                putExtra(NotificationHelper.EXTRA_DEEP_LINK, deepLink)
            }
        }
    }

    /**
     * Track A/B test click event if this notification is part of an A/B test.
     * Uses ABTestingManager if available, otherwise makes direct API call.
     */
    private fun trackABTestClick(context: Context, abTestId: String?, variantId: String?) {
        if (abTestId.isNullOrBlank() || variantId.isNullOrBlank()) {
            Log.d(TAG, "No A/B test data - skipping click tracking")
            return
        }

        Log.d(TAG, "Tracking A/B test click: testId=$abTestId, variantId=$variantId")

        // Track click in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Try using ABTestingManager first (if SDK is initialized)
                val manager = ABTestingManager.getInstance()
                if (manager != null) {
                    Log.d(TAG, "Using ABTestingManager to track click")
                    manager.trackClicked(abTestId, variantId,
                        onSuccess = { Log.d(TAG, "A/B test click tracked via manager") },
                        onError = { error -> Log.e(TAG, "Manager tracking failed: $error, trying direct API")
                            // Fallback to direct API call
                            trackClickDirectly(context, abTestId, variantId)
                        }
                    )
                } else {
                    // SDK not initialized - make direct API call
                    Log.d(TAG, "ABTestingManager not available, using direct API call")
                    trackClickDirectly(context, abTestId, variantId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to track A/B test click: ${e.message}")
                // Try direct API as last resort
                trackClickDirectly(context, abTestId, variantId)
            }
        }
    }

    /**
     * Make direct API call to track click (used when SDK not fully initialized)
     */
    private fun trackClickDirectly(context: Context, abTestId: String, variantId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apiKey = prefs.getString("apiKey", null)
            val deviceId = prefs.getString("device_id", null)

            if (apiKey.isNullOrBlank() || deviceId.isNullOrBlank()) {
                Log.e(TAG, "Cannot track click - missing apiKey or deviceId")
                return
            }

            Log.d(TAG, "Direct API call: apiKey=${apiKey.take(10)}..., deviceId=$deviceId")

            val json = JSONObject().apply {
                put("testId", abTestId)
                put("variantId", variantId)
                put("deviceId", deviceId)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${RiviumPushConfig.SERVER_URL}/ab-tests/sdk/track/clicked")
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "A/B test click tracked successfully via direct API")
                } else {
                    Log.e(TAG, "Direct API track failed: ${it.code} - ${it.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct API call failed: ${e.message}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "====== NOTIFICATION/ACTION CLICKED ======")
        Log.d(TAG, "Action: ${intent.action}")

        if (intent.action == NotificationHelper.ACTION_BUTTON_CLICKED) {
            val actionId = intent.getStringExtra(NotificationHelper.EXTRA_ACTION_ID)
            val messageId = intent.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID)
            val deepLink = intent.getStringExtra(NotificationHelper.EXTRA_DEEP_LINK)
            val abTestId = intent.getStringExtra(EXTRA_AB_TEST_ID)
            val variantId = intent.getStringExtra(EXTRA_VARIANT_ID)
            val messageJson = intent.getStringExtra("message_json")

            val isMainNotificationTap = actionId == "notification_tap"

            Log.d(TAG, "ActionId: $actionId ${if (isMainNotificationTap) "(main notification tap)" else ""}")
            Log.d(TAG, "MessageId: $messageId")
            Log.d(TAG, "DeepLink: $deepLink")
            Log.d(TAG, "ABTestId: $abTestId")
            Log.d(TAG, "VariantId: $variantId")
            Log.d(TAG, "HasMessageJson: ${!messageJson.isNullOrBlank()}")

            // Track A/B test click if this is an A/B test notification
            trackABTestClick(context, abTestId, variantId)

            // Handle initial message storage and callback notification
            if (isMainNotificationTap && !messageJson.isNullOrBlank()) {
                val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)

                // Check if the callback is available (app is running with SDK initialized)
                val callback = RiviumPushService.callback

                if (callback != null) {
                    // App is running - notify via callback and DON'T store
                    // (so it won't show again on restart)
                    Log.d(TAG, "App is running - notifying via callback, not storing initial message")
                    try {
                        val map = com.google.gson.Gson().fromJson(
                            messageJson,
                            object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                        ) as? Map<String, Any?>
                        if (map != null) {
                            val riviumPushMessage = RiviumPushMessage.fromMap(map)
                            if (riviumPushMessage != null) {
                                // Notify callback on main thread
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    callback.onNotificationTapped(riviumPushMessage)
                                }
                                Log.d(TAG, "Notified callback about notification tap")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to notify callback: ${e.message}")
                    }
                } else {
                    // App is NOT running - store for getInitialMessage()
                    Log.d(TAG, "App not running - storing initial message for getInitialMessage()")
                    prefs.edit()
                        .putString("initial_message", messageJson)
                        .apply()
                }
            }

            // Store the clicked action for the app to retrieve (skip for main tap)
            if (!isMainNotificationTap) {
                val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("clicked_action_id", actionId)
                    .putString("clicked_message_id", messageId)
                    .putString("clicked_deep_link", deepLink)
                    .apply()
            }

            // Launch the app
            val launchIntent = if (isMainNotificationTap) {
                // For main notification tap, just launch the app with data
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(NotificationHelper.EXTRA_MESSAGE_ID, messageId)
                    deepLink?.let { putExtra(NotificationHelper.EXTRA_DEEP_LINK, it) }
                    // Pass A/B test data to the activity
                    abTestId?.let { putExtra(EXTRA_AB_TEST_ID, it) }
                    variantId?.let { putExtra(EXTRA_VARIANT_ID, it) }
                }
            } else {
                createLaunchIntent(context, deepLink, actionId, messageId)
            }

            launchIntent?.let {
                try {
                    context.startActivity(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch activity: ${e.message}")
                    // Fallback to main activity
                    val fallbackIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(NotificationHelper.EXTRA_ACTION_ID, actionId)
                        putExtra(NotificationHelper.EXTRA_MESSAGE_ID, messageId)
                        putExtra(NotificationHelper.EXTRA_DEEP_LINK, deepLink)
                    }
                    fallbackIntent?.let { context.startActivity(it) }
                }
            }

            Log.d(TAG, "==========================================")
        }
    }
}
