package co.rivium.push.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives broadcast when the app is updated.
 * Triggers re-registration to refresh the device token.
 */
class AppUpdateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppUpdate"
        private const val PREFS_NAME = "rivium_push_prefs"
        private const val KEY_LAST_VERSION = "last_app_version"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        when (intent?.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "====== APP UPDATED ======")
                handleAppUpdate(context)
                Log.d(TAG, "=========================")
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "====== BOOT COMPLETED ======")
                checkVersionChange(context)
                Log.d(TAG, "============================")
            }
        }
    }

    private fun handleAppUpdate(context: Context) {
        Log.d(TAG, "App was updated - marking for re-registration")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Store current version
        val currentVersion = getAppVersion(context)
        val lastVersion = prefs.getString(KEY_LAST_VERSION, null)

        Log.d(TAG, "Last version: $lastVersion")
        Log.d(TAG, "Current version: $currentVersion")

        // Mark that re-registration is needed
        prefs.edit()
            .putBoolean("needs_reregistration", true)
            .putString(KEY_LAST_VERSION, currentVersion)
            .apply()

        // Try to restart the service if config exists
        restartServiceIfPossible(context)
    }

    private fun checkVersionChange(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val currentVersion = getAppVersion(context)
        val lastVersion = prefs.getString(KEY_LAST_VERSION, null)

        Log.d(TAG, "Boot check - Last version: $lastVersion, Current: $currentVersion")

        if (lastVersion != null && lastVersion != currentVersion) {
            Log.d(TAG, "Version changed during boot - marking for re-registration")
            prefs.edit()
                .putBoolean("needs_reregistration", true)
                .putString(KEY_LAST_VERSION, currentVersion)
                .apply()
        }

        // Try to restart service
        restartServiceIfPossible(context)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName}-${packageInfo.longVersionCode}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version: ${e.message}")
            "unknown"
        }
    }

    private fun restartServiceIfPossible(context: Context) {
        // Check if we have saved config
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("apiKey", null)

        if (apiKey != null) {
            Log.d(TAG, "Config exists - service will be started by the app")
            // The app will handle starting the service when it launches
        } else {
            Log.d(TAG, "No saved config - waiting for app to initialize")
        }
    }
}
