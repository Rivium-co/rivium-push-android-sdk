package co.rivium.push.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receiver to restart RiviumPush service after device boot or app update.
 * Restores configuration from SharedPreferences and restarts the push connection.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "RiviumPushBootReceiver"
        private const val PREFS_NAME = "rivium_push_prefs"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Device booted or app updated - attempting to restart RiviumPush service")
                restartService(context)
            }
        }
    }

    private fun restartService(context: Context) {
        // Load persisted config
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("apiKey", null)
        val deviceId = prefs.getString("device_id", null)

        if (apiKey != null && deviceId != null) {
            // Restore config from SharedPreferences
            val config = RiviumPushConfig(
                apiKey = apiKey,
                notificationIcon = prefs.getString("notificationIcon", null),
                showServiceNotification = prefs.getBoolean("showServiceNotification", true)
            )
            // Restore internal PN host and JWT token if available
            val pnHost = prefs.getString("pnHost", "") ?: ""
            if (pnHost.isNotEmpty()) {
                val pnToken = prefs.getString("pnToken", null)
                config.updatePushConfig(pnHost, token = pnToken)
            }

            RiviumPushService.config = config
            RiviumPushService.deviceId = deviceId
            // Use saved appId from server (projectId-based), fallback to apiKey prefix for legacy
            val savedAppId = prefs.getString("appId", null)
            RiviumPushService.appId = savedAppId ?: apiKey.take(16)
            // Restore appIdentifier (packageName) for correct pn-protocol topic subscription
            val savedAppIdentifier = prefs.getString("appIdentifier", null)
            RiviumPushService.appIdentifier = savedAppIdentifier ?: context.packageName

            val serviceIntent = Intent(context, RiviumPushService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "RiviumPush service restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart RiviumPush service: ${e.message}")
            }
        } else {
            Log.d(TAG, "RiviumPush not configured - service not started")
        }
    }
}
