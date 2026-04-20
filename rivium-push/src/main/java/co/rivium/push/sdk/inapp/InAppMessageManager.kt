package co.rivium.push.sdk.inapp

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import co.rivium.push.sdk.ApiClient
import co.rivium.push.sdk.RiviumPushExecutors
import co.rivium.push.sdk.RiviumPushLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Callback interface for in-app message events
 */
interface InAppMessageCallback {
    /**
     * Called when an in-app message is ready to be displayed
     */
    fun onMessageReady(message: InAppMessage)

    /**
     * Called when a button is clicked
     */
    fun onButtonClicked(message: InAppMessage, button: InAppButton)

    /**
     * Called when the message is dismissed
     */
    fun onMessageDismissed(message: InAppMessage)

    /**
     * Called when there's an error
     */
    fun onError(error: String)
}

/**
 * Manages in-app messages: fetching, caching, triggering, and tracking
 */
class InAppMessageManager private constructor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val appId: String,
    private val deviceId: String
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var cachedMessages: List<InAppMessage> = emptyList()
    private val impressionCounts: MutableMap<String, Int> = ConcurrentHashMap()
    private var sessionCount: Int = 0
    private var userId: String? = null
    private var callback: InAppMessageCallback? = null
    private var currentActivity: Activity? = null
    private var isShowingMessage = false

    // Currently displayed message view
    private var currentMessageView: InAppMessageView? = null

    companion object {
        private const val TAG = "RiviumPush.InApp"
        private const val PREFS_NAME = "rivium_push_inapp"
        private const val KEY_IMPRESSIONS = "impressions"
        private const val KEY_SESSION_COUNT = "session_count"
        private const val KEY_CACHED_MESSAGES = "cached_messages"
        private const val KEY_LAST_FETCH = "last_fetch"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: InAppMessageManager? = null

        fun getInstance(
            context: Context,
            apiClient: ApiClient,
            appId: String,
            deviceId: String
        ): InAppMessageManager {
            return instance ?: synchronized(this) {
                instance ?: InAppMessageManager(context, apiClient, appId, deviceId).also {
                    instance = it
                }
            }
        }

        fun getInstance(): InAppMessageManager? = instance
    }

    init {
        loadImpressionCounts()
        sessionCount = prefs.getInt(KEY_SESSION_COUNT, 0)
        loadCachedMessages()
    }

    /**
     * Set the callback for in-app message events
     */
    fun setCallback(callback: InAppMessageCallback?) {
        this.callback = callback
    }

    /**
     * Set the current activity for displaying messages
     */
    fun setCurrentActivity(activity: Activity?) {
        this.currentActivity = activity
    }

    /**
     * Set the user ID for targeting
     */
    fun setUserId(userId: String?) {
        this.userId = userId
    }

    /**
     * Increment session count (call when app starts)
     */
    fun incrementSessionCount() {
        sessionCount++
        // Save to SharedPreferences on background thread to avoid ANR
        RiviumPushExecutors.executeIO {
            prefs.edit().putInt(KEY_SESSION_COUNT, sessionCount).apply()
        }
        RiviumPushLogger.d(TAG, "Session count: $sessionCount")
    }

    /**
     * Fetch all messages from server (Google/Firebase approach)
     * Messages are fetched without trigger filter and filtered locally
     */
    fun fetchMessages(onComplete: ((List<InAppMessage>) -> Unit)? = null) {
        RiviumPushLogger.d(TAG, "fetchMessages() called, deviceId=$deviceId")
        RiviumPushExecutors.executeNetwork {
            try {
                // Fetch ALL messages without trigger filter (like Firebase)
                // Local filtering happens in findAndShowMessage()
                val params = mutableMapOf<String, Any?>(
                    "deviceId" to deviceId,
                    "sessionCount" to sessionCount,
                    "locale" to Locale.getDefault().toLanguageTag()
                )
                userId?.let { params["userId"] = it }

                RiviumPushLogger.d(TAG, "Fetching in-app messages with params: $params")
                val response = apiClient.getInAppMessages(params)
                RiviumPushLogger.d(TAG, "API response: $response")

                if (response != null) {
                    val messages = parseMessages(response)
                    cachedMessages = messages
                    // Save to SharedPreferences on IO thread
                    RiviumPushExecutors.executeIO {
                        saveCachedMessages(messages)
                        prefs.edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply()
                    }

                    RiviumPushLogger.d(TAG, "Fetched ${messages.size} in-app messages")
                    messages.forEach { msg ->
                        RiviumPushLogger.d(TAG, "  - Message: id=${msg.id}, name=${msg.name}, trigger=${msg.triggerType}, type=${msg.type}")
                    }

                    RiviumPushExecutors.executeMain {
                        onComplete?.invoke(messages)
                    }
                } else {
                    RiviumPushLogger.w(TAG, "API returned null response, using cached messages (${cachedMessages.size})")
                    RiviumPushExecutors.executeMain {
                        onComplete?.invoke(cachedMessages)
                    }
                }
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Failed to fetch messages", e)
                RiviumPushExecutors.executeMain {
                    callback?.onError("Failed to fetch messages: ${e.message}")
                    onComplete?.invoke(cachedMessages)
                }
            }
        }
    }

    /**
     * Trigger messages for app open event
     */
    fun triggerOnAppOpen() {
        RiviumPushLogger.d(TAG, "Triggering on_app_open")
        triggerMessages(InAppTriggerType.ON_APP_OPEN)
    }

    /**
     * Trigger messages for a custom event
     */
    fun triggerEvent(eventName: String, properties: Map<String, Any>? = null) {
        RiviumPushLogger.d(TAG, "Triggering event: $eventName")
        triggerMessages(InAppTriggerType.ON_EVENT, eventName)
    }

    /**
     * Trigger messages for session start
     */
    fun triggerOnSessionStart() {
        RiviumPushLogger.d(TAG, "Triggering on_session_start")
        incrementSessionCount()
        triggerMessages(InAppTriggerType.ON_SESSION_START)
    }

    /**
     * Show a specific message manually
     */
    fun showMessage(messageId: String) {
        val message = cachedMessages.find { it.id == messageId }
        if (message != null) {
            showMessageInternal(message)
        } else {
            RiviumPushLogger.w(TAG, "Message not found: $messageId")
        }
    }

    /**
     * Dismiss the currently displayed message
     */
    fun dismissCurrentMessage() {
        currentMessageView?.dismiss()
        currentMessageView = null
        isShowingMessage = false
    }

    /**
     * Record an impression
     */
    fun recordImpression(
        messageId: String,
        action: InAppImpressionAction,
        buttonId: String? = null
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val params = mapOf(
                    "messageId" to messageId,
                    "deviceId" to deviceId,
                    "action" to action.value,
                    "buttonId" to (buttonId ?: ""),
                    "userId" to (userId ?: "")
                )

                apiClient.recordInAppImpression(params)

                // Update local impression count
                if (action == InAppImpressionAction.IMPRESSION) {
                    val count = impressionCounts.getOrDefault(messageId, 0) + 1
                    impressionCounts[messageId] = count
                    // Save to SharedPreferences on IO thread
                    RiviumPushExecutors.executeIO {
                        saveImpressionCounts()
                    }
                }

                RiviumPushLogger.d(TAG, "Recorded impression: $action for $messageId")
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Failed to record impression", e)
            }
        }
    }

    /**
     * Clear all cached data
     */
    fun clearCache() {
        cachedMessages = emptyList()
        impressionCounts.clear()
        RiviumPushExecutors.executeIO {
            prefs.edit().clear().apply()
        }
        RiviumPushLogger.d(TAG, "Cache cleared")
    }

    // ==================== Private Methods ====================

    private fun triggerMessages(triggerType: InAppTriggerType, eventName: String? = null) {
        RiviumPushLogger.d(TAG, "triggerMessages() called - triggerType=$triggerType, eventName=$eventName")
        RiviumPushLogger.d(TAG, "  isShowingMessage=$isShowingMessage, currentActivity=${currentActivity?.javaClass?.simpleName}")

        if (isShowingMessage) {
            RiviumPushLogger.d(TAG, "Already showing a message, skipping trigger")
            return
        }

        // Check if cache is fresh enough
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        val cacheAge = System.currentTimeMillis() - lastFetch
        RiviumPushLogger.d(TAG, "Cache age: ${cacheAge}ms (TTL: ${CACHE_TTL_MS}ms)")

        if (cacheAge > CACHE_TTL_MS) {
            RiviumPushLogger.d(TAG, "Cache expired, fetching fresh messages...")
            // Fetch fresh messages first
            fetchMessages { messages ->
                findAndShowMessage(messages, triggerType, eventName)
            }
        } else {
            RiviumPushLogger.d(TAG, "Using cached messages (${cachedMessages.size} messages)")
            findAndShowMessage(cachedMessages, triggerType, eventName)
        }
    }

    private fun findAndShowMessage(
        messages: List<InAppMessage>,
        triggerType: InAppTriggerType,
        eventName: String?
    ) {
        RiviumPushLogger.d(TAG, "findAndShowMessage() - triggerType=$triggerType, eventName=$eventName")
        RiviumPushLogger.d(TAG, "Total messages to check: ${messages.size}")

        val eligibleMessages = messages.filter { message ->
            RiviumPushLogger.d(TAG, "Checking message: ${message.id} (${message.name})")

            // Check trigger type
            if (message.triggerType != triggerType) {
                RiviumPushLogger.d(TAG, "  - SKIP: trigger type mismatch (${message.triggerType} != $triggerType)")
                return@filter false
            }

            // Check event name for ON_EVENT trigger
            if (triggerType == InAppTriggerType.ON_EVENT) {
                if (message.triggerEvent != eventName) {
                    RiviumPushLogger.d(TAG, "  - SKIP: event name mismatch (${message.triggerEvent} != $eventName)")
                    return@filter false
                }
            }

            // Check session count
            if (sessionCount < message.minSessionCount) {
                RiviumPushLogger.d(TAG, "  - SKIP: session count too low ($sessionCount < ${message.minSessionCount})")
                return@filter false
            }

            // Check impression limit
            val impressions = impressionCounts.getOrDefault(message.id, 0)
            if (impressions >= message.maxImpressions) {
                RiviumPushLogger.d(TAG, "  - SKIP: impression limit reached ($impressions >= ${message.maxImpressions})")
                return@filter false
            }

            // Check date range
            val now = System.currentTimeMillis()
            message.startDate?.let {
                if (now < it) {
                    RiviumPushLogger.d(TAG, "  - SKIP: before start date ($now < $it)")
                    return@filter false
                }
            }
            message.endDate?.let {
                if (now > it) {
                    RiviumPushLogger.d(TAG, "  - SKIP: after end date ($now > $it)")
                    return@filter false
                }
            }

            RiviumPushLogger.d(TAG, "  - ELIGIBLE: message passes all checks")
            true
        }.sortedByDescending { it.priority }

        RiviumPushLogger.d(TAG, "Eligible messages: ${eligibleMessages.size}")

        val messageToShow = eligibleMessages.firstOrNull()
        if (messageToShow != null) {
            RiviumPushLogger.d(TAG, "Will show message: ${messageToShow.id} (${messageToShow.name})")
            // Apply delay if configured
            if (messageToShow.delaySeconds > 0) {
                RiviumPushLogger.d(TAG, "Delaying message display by ${messageToShow.delaySeconds} seconds")
                RiviumPushExecutors.executeMainDelayed(messageToShow.delaySeconds * 1000L) {
                    showMessageInternal(messageToShow)
                }
            } else {
                showMessageInternal(messageToShow)
            }
        } else {
            RiviumPushLogger.d(TAG, "No eligible messages to show")
        }
    }

    private fun showMessageInternal(message: InAppMessage) {
        RiviumPushLogger.d(TAG, "showMessageInternal() called for message: ${message.id} (${message.name})")

        isShowingMessage = true

        RiviumPushExecutors.executeMain {
            try {
                // Get localized content
                val locale = Locale.getDefault().toLanguageTag()
                val content = message.getLocalizedContent(locale)
                RiviumPushLogger.d(TAG, "Showing message with content: title='${content.title}', body='${content.body}', backgroundColor='${content.backgroundColor}', textColor='${content.textColor}'")

                // Check if a callback is set (Flutter/React Native manages UI)
                if (callback != null) {
                    // Callback is set - let the wrapper (Flutter/RN) handle the UI
                    // We only record impression and notify callback, no native UI shown
                    RiviumPushLogger.d(TAG, "Callback is set - delegating UI to wrapper (Flutter/React Native)")

                    // Record impression
                    recordImpression(message.id, InAppImpressionAction.IMPRESSION)

                    // Notify callback - wrapper will show its own UI
                    callback?.onMessageReady(message)
                } else {
                    // No callback - show native Android UI
                    RiviumPushLogger.d(TAG, "No callback set - showing native Android UI")

                    val activity = currentActivity
                    if (activity == null) {
                        RiviumPushLogger.w(TAG, "No activity available to show message (activity is null)")
                        isShowingMessage = false
                        return@executeMain
                    }
                    if (activity.isFinishing) {
                        RiviumPushLogger.w(TAG, "Activity is finishing, cannot show message")
                        isShowingMessage = false
                        return@executeMain
                    }

                    RiviumPushLogger.d(TAG, "Activity available: ${activity.javaClass.simpleName}")

                    // Create and show the native message view
                    currentMessageView = InAppMessageView.show(
                        activity = activity,
                        message = message,
                        content = content,
                        onButtonClick = { button ->
                            handleButtonClick(message, button)
                        },
                        onDismiss = {
                            handleDismiss(message)
                        }
                    )

                    RiviumPushLogger.d(TAG, "Message view created successfully")

                    // Record impression
                    recordImpression(message.id, InAppImpressionAction.IMPRESSION)
                }
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Error showing message", e)
                isShowingMessage = false
            }
        }
    }

    private fun handleButtonClick(message: InAppMessage, button: InAppButton) {
        RiviumPushLogger.d(TAG, "Button clicked: ${button.id} - ${button.action}")

        // Record button click
        recordImpression(message.id, InAppImpressionAction.BUTTON_CLICK, button.id)

        // Handle action
        when (button.action) {
            InAppButtonAction.DISMISS -> {
                dismissCurrentMessage()
            }
            InAppButtonAction.DEEP_LINK -> {
                button.value?.let { deepLink ->
                    handleDeepLink(deepLink)
                }
                dismissCurrentMessage()
            }
            InAppButtonAction.URL -> {
                button.value?.let { url ->
                    handleUrl(url)
                }
                dismissCurrentMessage()
            }
            InAppButtonAction.CUSTOM -> {
                // Let the callback handle custom actions
                callback?.onButtonClicked(message, button)
            }
        }
    }

    private fun handleDismiss(message: InAppMessage) {
        RiviumPushLogger.d(TAG, "Message dismissed: ${message.id}")
        isShowingMessage = false
        currentMessageView = null

        // Record dismiss
        recordImpression(message.id, InAppImpressionAction.DISMISS)

        // Notify callback
        callback?.onMessageDismissed(message)
    }

    private fun handleDeepLink(deepLink: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(deepLink)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to open deep link: $deepLink", e)
        }
    }

    private fun handleUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(url)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to open URL: $url", e)
        }
    }

    private fun parseMessages(json: String): List<InAppMessage> {
        val messages = mutableListOf<InAppMessage>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                messages.add(InAppMessage.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to parse messages", e)
        }
        return messages
    }

    private fun loadCachedMessages() {
        try {
            val json = prefs.getString(KEY_CACHED_MESSAGES, null)
            if (json != null) {
                cachedMessages = parseMessages(json)
            }
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to load cached messages", e)
        }
    }

    private fun saveCachedMessages(messages: List<InAppMessage>) {
        try {
            val array = JSONArray()
            messages.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_CACHED_MESSAGES, array.toString()).apply()
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to save cached messages", e)
        }
    }

    private fun loadImpressionCounts() {
        try {
            val json = prefs.getString(KEY_IMPRESSIONS, null)
            if (json != null) {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    impressionCounts[key] = obj.getInt(key)
                }
            }
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to load impression counts", e)
        }
    }

    private fun saveImpressionCounts() {
        try {
            val obj = JSONObject()
            impressionCounts.forEach { (key, value) ->
                obj.put(key, value)
            }
            prefs.edit().putString(KEY_IMPRESSIONS, obj.toString()).apply()
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to save impression counts", e)
        }
    }
}
