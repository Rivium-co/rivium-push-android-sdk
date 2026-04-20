package co.rivium.push.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

/**
 * Helper class for displaying notifications with rich content support.
 */
class NotificationHelper(
    private val context: Context,
    private val config: RiviumPushConfig
) {
    companion object {
        private const val TAG = "Notification"
        private const val CHANNEL_ID = "rivium_push_channel"
        private const val SERVICE_CHANNEL_ID = "rivium_push_service_channel"
        const val ACTION_BUTTON_CLICKED = "co.rivium.push.ACTION_CLICKED"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_DEEP_LINK = "deep_link"

        /**
         * Get the message that launched the app (when user tapped a notification)
         */
        fun getInitialMessage(context: Context): RiviumPushMessage? {
            val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("initial_message", null) ?: return null

            val map = com.google.gson.Gson().fromJson(
                json,
                object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            ) as? Map<String, Any?> ?: return null

            return RiviumPushMessage.fromMap(map)
        }

        /**
         * Check if the service notification channel is enabled.
         * Returns false if the user has disabled it (notification hidden).
         */
        fun isServiceChannelEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = manager.getNotificationChannel(SERVICE_CHANNEL_ID)
                return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
            }
            return true
        }

        /**
         * Get the service notification channel ID
         */
        fun getServiceChannelId(): String = SERVICE_CHANNEL_ID

        /**
         * Clear the initial message after reading
         */
        fun clearInitialMessage(context: Context) {
            RiviumPushExecutors.executeIO {
                val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("initial_message").apply()
            }
        }

        /**
         * Get the action that was clicked
         */
        fun getClickedAction(context: Context): Map<String, String?>? {
            val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
            val actionId = prefs.getString("clicked_action_id", null) ?: return null
            val messageId = prefs.getString("clicked_message_id", null)
            val deepLink = prefs.getString("clicked_deep_link", null)

            // Clear after reading on background thread
            RiviumPushExecutors.executeIO {
                prefs.edit()
                    .remove("clicked_action_id")
                    .remove("clicked_message_id")
                    .remove("clicked_deep_link")
                    .apply()
            }

            return mapOf(
                "actionId" to actionId,
                "messageId" to messageId,
                "deepLink" to deepLink
            )
        }
    }

    private var notificationId = 1000

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            // Push notifications channel
            val channel = NotificationChannel(
                CHANNEL_ID,
                config.notificationChannelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notifications"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)

            // Service channel - use MIN importance when hiding notification
            val serviceImportance = if (config.showServiceNotification) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_MIN
            }
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Push Service",
                serviceImportance
            ).apply {
                description = "Keeps push notifications active"
                setShowBadge(false)
                if (!config.showServiceNotification) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Show a push notification with rich content support
     */
    fun showNotification(message: RiviumPushMessage) {
        // Note: We no longer store the message here.
        // The initial message is only stored when the user actually taps the notification.
        // This is handled in NotificationActionReceiver for action buttons,
        // or in the content intent PendingIntent for the main notification tap.

        // Get localized content based on device locale
        val deviceLocale = Locale.getDefault().language
        val title = message.getLocalizedTitle(deviceLocale)
        val body = message.getLocalizedBody(deviceLocale)

        // Check if we need to download an image
        if (message.imageUrl != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = downloadImage(message.imageUrl)
                withContext(Dispatchers.Main) {
                    showNotificationWithBitmap(message, title, body, bitmap)
                }
            }
        } else {
            showNotificationWithBitmap(message, title, body, null)
        }
    }

    private fun showNotificationWithBitmap(
        message: RiviumPushMessage,
        title: String,
        body: String,
        imageBitmap: Bitmap?
    ) {
        val currentNotificationId = notificationId++

        // Check if this is an A/B test notification
        val abTestId = message.data?.get("abTestId")?.toString()
        val variantId = message.data?.get("variantId")?.toString()

        // Always route through receiver so we can properly store the initial message
        // when the notification is tapped (not when it's displayed)
        val messageJson = com.google.gson.Gson().toJson(message.toMap())
        val clickIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_BUTTON_CLICKED
            putExtra(EXTRA_ACTION_ID, "notification_tap") // Special ID for main notification tap
            putExtra(EXTRA_MESSAGE_ID, message.messageId)
            putExtra(EXTRA_DEEP_LINK, message.deepLink)
            putExtra("message_json", messageJson) // Pass full message for getInitialMessage()
            // Pass A/B test data if present
            abTestId?.let { putExtra(NotificationActionReceiver.EXTRA_AB_TEST_ID, it) }
            variantId?.let { putExtra(NotificationActionReceiver.EXTRA_VARIANT_ID, it) }
            // Pass custom data as well
            message.data?.forEach { (key, value) ->
                putExtra("data_$key", value.toString())
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            currentNotificationId,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = getNotificationIcon()

        // Use a custom notification channel if a custom sound is specified (Android 8+)
        val channelId = getOrCreateSoundChannel(message.sound)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // Set priority
        val priority = when (message.priority) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        builder.setPriority(priority)

        // Add large image if available
        if (imageBitmap != null) {
            builder.setLargeIcon(imageBitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(imageBitmap)
                    .bigLargeIcon(null as Bitmap?)
            )
        } else {
            // Use big text style for long messages
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        // Set group/thread for collapsing
        message.collapseKey?.let { builder.setGroup(it) }
        message.threadId?.let { builder.setGroup(it) }

        // Add action buttons (max 3)
        message.actions?.take(3)?.forEachIndexed { index, action ->
            val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = ACTION_BUTTON_CLICKED
                putExtra(EXTRA_ACTION_ID, action.id)
                putExtra(EXTRA_MESSAGE_ID, message.messageId)
                putExtra(EXTRA_DEEP_LINK, action.action ?: message.deepLink)
                // Pass A/B test IDs for automatic click tracking
                message.data?.get("abTestId")?.toString()?.let { putExtra(NotificationActionReceiver.EXTRA_AB_TEST_ID, it) }
                message.data?.get("variantId")?.toString()?.let { putExtra(NotificationActionReceiver.EXTRA_VARIANT_ID, it) }
            }

            val actionPendingIntent = PendingIntent.getBroadcast(
                context,
                currentNotificationId * 10 + index,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val actionIcon = action.icon?.let {
                context.resources.getIdentifier(it, "drawable", context.packageName)
            } ?: 0

            builder.addAction(actionIcon, action.title, actionPendingIntent)
        }

        // Set sound
        if (message.sound == "default") {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        } else if (!message.sound.isNullOrEmpty()) {
            // Custom sound from app's res/raw/ folder
            val soundResId = context.resources.getIdentifier(
                message.sound, "raw", context.packageName
            )
            if (soundResId != 0) {
                val soundUri = Uri.parse(
                    "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$soundResId"
                )
                builder.setSound(soundUri)
                Log.d(TAG, "Custom sound set: ${message.sound}")
            } else {
                Log.w(TAG, "Custom sound '${message.sound}' not found in res/raw/, using default")
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(currentNotificationId, builder.build())
            Log.d(TAG, "Notification shown: id=$currentNotificationId, title=$title")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted")
        }

        // Handle badge count (for supported launchers)
        message.badge?.let { badgeCount ->
            updateBadgeCount(badgeCount, message.badgeAction ?: "set")
        }
    }

    /**
     * Get or create a notification channel for the given sound.
     * On Android 8+, notification sound is controlled by the channel, not the builder.
     * Returns the appropriate channel ID.
     */
    private fun getOrCreateSoundChannel(sound: String?): String {
        // No custom sound or "default" — use the standard channel
        if (sound.isNullOrEmpty() || sound == "default") {
            return CHANNEL_ID
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CHANNEL_ID
        }

        // Look up the raw resource
        val soundResId = context.resources.getIdentifier(sound, "raw", context.packageName)
        if (soundResId == 0) {
            Log.w(TAG, "Sound '$sound' not found in res/raw/, using default channel")
            return CHANNEL_ID
        }

        val customChannelId = "rivium_push_sound_$sound"
        val manager = context.getSystemService(NotificationManager::class.java)

        // Only create if it doesn't exist yet
        if (manager.getNotificationChannel(customChannelId) == null) {
            val soundUri = Uri.parse(
                "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$soundResId"
            )
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                customChannelId,
                "${config.notificationChannelName} - ${sound.replaceFirstChar { it.uppercase() }}",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notifications with custom sound"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Created custom sound channel: $customChannelId")
        }

        return customChannelId
    }

    private fun downloadImage(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.getInputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image: ${e.message}")
            null
        }
    }

    private fun updateBadgeCount(count: Int, action: String) {
        try {
            val currentBadge = getBadgeCount()
            val newCount = when (action) {
                "increment" -> currentBadge + count
                "decrement" -> maxOf(0, currentBadge - count)
                "clear" -> 0
                else -> count // "set"
            }

            // Store badge count on background thread to avoid ANR
            RiviumPushExecutors.executeIO {
                val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("badge_count", newCount).apply()
            }

            // Try to update launcher badge (Samsung, etc.)
            val launcherIntent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
            launcherIntent.putExtra("badge_count", newCount)
            launcherIntent.putExtra("badge_count_package_name", context.packageName)
            launcherIntent.putExtra("badge_count_class_name", getLauncherClassName())
            context.sendBroadcast(launcherIntent)

            Log.d(TAG, "Badge count updated: $newCount (action=$action)")
        } catch (e: Exception) {
            Log.e(TAG, "Badge update failed: ${e.message}")
        }
    }

    private fun getBadgeCount(): Int {
        val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("badge_count", 0)
    }

    private fun getLauncherClassName(): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.setPackage(context.packageName)
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        return if (resolveInfos.isNotEmpty()) {
            resolveInfos[0].activityInfo.name
        } else {
            ""
        }
    }

    /**
     * Get notification icon resource, checking drawable first then mipmap
     */
    private fun getNotificationIcon(): Int {
        val iconName = config.notificationIcon ?: return android.R.drawable.ic_dialog_info

        // Try drawable folder first
        var iconRes = context.resources.getIdentifier(iconName, "drawable", context.packageName)
        if (iconRes != 0) return iconRes

        // Try mipmap folder (where launcher icons are typically stored)
        iconRes = context.resources.getIdentifier(iconName, "mipmap", context.packageName)
        if (iconRes != 0) return iconRes

        // Fallback to system default
        Log.w(TAG, "Notification icon '$iconName' not found in drawable or mipmap, using default")
        return android.R.drawable.ic_dialog_info
    }

    /**
     * Create foreground service notification
     */
    fun createServiceNotification(): android.app.Notification {
        val iconRes = getNotificationIcon()

        return if (config.showServiceNotification) {
            // Standard visible notification
            NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle("Push notifications active")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        } else {
            // Minimal/hidden notification - required for foreground service but less intrusive
            NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }

}
