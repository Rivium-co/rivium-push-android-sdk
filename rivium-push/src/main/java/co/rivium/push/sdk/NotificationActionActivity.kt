package co.rivium.push.sdk

import android.app.Activity
import android.os.Bundle

/**
 * Invisible activity opened by notification action buttons. Not for app use.
 *
 * Android 12+ blocks starting an activity from a BroadcastReceiver or service
 * that a notification started (a "notification trampoline"), so an action
 * button that opens the app must open an activity itself. This one runs the
 * same handling as before (A/B click tracking, the clicked receipt, the
 * action callback, getClickedAction()), opens the app or the action's deep
 * link, and finishes at once.
 */
class NotificationActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val tapIntent = intent
            if (savedInstanceState == null && tapIntent?.action == NotificationHelper.ACTION_BUTTON_CLICKED) {
                NotificationActionReceiver.handleTap(applicationContext, tapIntent) { startActivity(it) }
            }
        } catch (e: Exception) {
            Log.e("ActionActivity", "Failed to handle the action tap: ${e.message}")
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
