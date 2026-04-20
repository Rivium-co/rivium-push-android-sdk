package co.rivium.push.sdk.inbox

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.push.sdk.Log
import co.rivium.push.sdk.RiviumPushConfig
import co.rivium.push.sdk.RiviumPushExecutors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Manager for inbox messages.
 * Handles fetching, caching, and updating inbox messages.
 */
class InboxManager private constructor(
    private val context: Context,
    private val config: RiviumPushConfig,
    private val deviceId: String,
    private var userId: String? = null
) {
    companion object {
        private const val TAG = "Inbox"
        private const val PREFS_NAME = "rivium_push_inbox_prefs"
        private const val KEY_CACHED_MESSAGES = "cached_messages"
        private const val KEY_UNREAD_COUNT = "unread_count"

        @Volatile
        private var instance: InboxManager? = null

        fun getInstance(
            context: Context,
            config: RiviumPushConfig,
            deviceId: String,
            userId: String? = null
        ): InboxManager {
            return instance ?: synchronized(this) {
                instance ?: InboxManager(context.applicationContext, config, deviceId, userId).also {
                    instance = it
                }
            }
        }
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var callback: InboxCallback? = null
    private var cachedMessages: MutableList<InboxMessage> = mutableListOf()
    private var unreadCount: Int = 0

    init {
        loadCachedData()
    }

    /**
     * Set the callback for inbox events.
     */
    fun setCallback(callback: InboxCallback?) {
        this.callback = callback
    }

    /**
     * Update the user ID for inbox queries.
     */
    fun setUserId(userId: String?) {
        this.userId = userId
    }

    /**
     * Get inbox messages with optional filters.
     */
    fun getMessages(
        filter: InboxFilter = InboxFilter(),
        onSuccess: (InboxMessagesResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val params = mutableMapOf<String, Any?>()

                // Use userId if available, otherwise deviceId
                if (userId != null) {
                    params["userId"] = userId
                } else {
                    params["deviceId"] = deviceId
                }

                filter.status?.let { params["status"] = it.name.lowercase() }
                filter.category?.let { params["category"] = it }
                params["limit"] = filter.limit
                params["offset"] = filter.offset
                filter.locale?.let { params["locale"] = it }

                val body = gson.toJson(params).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages")
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                Log.d(TAG, "Fetching inbox messages - userId=$userId, deviceId=$deviceId, params=$params")

                val response = client.newCall(request).execute()
                response.use {
                    val responseBody = it.body?.string() ?: ""
                    Log.d(TAG, "Inbox response: code=${it.code}, body=$responseBody")
                    if (it.isSuccessful) {
                        val messagesResponse = parseMessagesResponse(responseBody)
                        Log.d(TAG, "Parsed ${messagesResponse.messages.size} messages, total=${messagesResponse.total}, unread=${messagesResponse.unreadCount}")

                        // Cache the messages
                        if (filter.offset == 0) {
                            cachedMessages.clear()
                        }
                        cachedMessages.addAll(messagesResponse.messages)
                        unreadCount = messagesResponse.unreadCount
                        saveCachedData()

                        RiviumPushExecutors.executeMain {
                            onSuccess(messagesResponse)
                        }
                    } else {
                        Log.e(TAG, "Fetch messages failed: ${it.code} - $responseBody")
                        RiviumPushExecutors.executeMain {
                            onError("Failed to fetch messages: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch messages error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Get a single message by ID.
     */
    fun getMessage(
        messageId: String,
        onSuccess: (InboxMessage) -> Unit,
        onError: (String) -> Unit
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages/$messageId")
                    .addHeader("x-api-key", config.apiKey)
                    .get()
                    .build()

                Log.d(TAG, "Fetching inbox message: $messageId")

                val response = client.newCall(request).execute()
                response.use {
                    val responseBody = it.body?.string() ?: ""
                    if (it.isSuccessful) {
                        val message = gson.fromJson(responseBody, InboxMessage::class.java)
                        RiviumPushExecutors.executeMain {
                            onSuccess(message)
                        }
                    } else {
                        Log.e(TAG, "Fetch message failed: ${it.code}")
                        RiviumPushExecutors.executeMain {
                            onError("Failed to fetch message: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch message error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Mark a message as read.
     */
    fun markAsRead(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        updateMessageStatus(messageId, InboxMessageStatus.READ, onSuccess, onError)
    }

    /**
     * Archive a message.
     */
    fun archiveMessage(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        updateMessageStatus(messageId, InboxMessageStatus.ARCHIVED, onSuccess, onError)
    }

    /**
     * Delete a message.
     */
    fun deleteMessage(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages/$messageId")
                    .addHeader("x-api-key", config.apiKey)
                    .delete()
                    .build()

                Log.d(TAG, "Deleting inbox message: $messageId")

                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        // Update local cache
                        cachedMessages.removeAll { msg -> msg.id == messageId }
                        saveCachedData()

                        RiviumPushExecutors.executeMain {
                            callback?.onMessageStatusChanged(messageId, InboxMessageStatus.DELETED)
                            onSuccess?.invoke()
                        }
                    } else {
                        Log.e(TAG, "Delete message failed: ${it.code}")
                        RiviumPushExecutors.executeMain {
                            onError?.invoke("Failed to delete message: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete message error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Mark multiple messages with a status.
     */
    fun markMultiple(
        messageIds: List<String>,
        status: InboxMessageStatus,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val params = mapOf(
                    "messageIds" to messageIds,
                    "status" to status.name.lowercase()
                )
                val body = gson.toJson(params).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages/mark-multiple")
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                Log.d(TAG, "Marking ${messageIds.size} messages as ${status.name}")

                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        // Update local cache
                        messageIds.forEach { id ->
                            val index = cachedMessages.indexOfFirst { msg -> msg.id == id }
                            if (index >= 0) {
                                cachedMessages[index] = cachedMessages[index].copy(status = status)
                            }
                        }
                        if (status == InboxMessageStatus.READ) {
                            unreadCount = maxOf(0, unreadCount - messageIds.size)
                        }
                        saveCachedData()

                        RiviumPushExecutors.executeMain {
                            messageIds.forEach { id ->
                                callback?.onMessageStatusChanged(id, status)
                            }
                            onSuccess?.invoke()
                        }
                    } else {
                        Log.e(TAG, "Mark multiple failed: ${it.code}")
                        RiviumPushExecutors.executeMain {
                            onError?.invoke("Failed to mark messages: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mark multiple error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Mark all messages as read.
     */
    fun markAllAsRead(
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val params = mutableMapOf<String, String?>()
                if (userId != null) {
                    params["userId"] = userId
                } else {
                    params["deviceId"] = deviceId
                }
                val body = gson.toJson(params).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages/mark-all-read")
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                Log.d(TAG, "Marking all messages as read")

                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        // Update local cache
                        cachedMessages = cachedMessages.map { msg ->
                            if (msg.status == InboxMessageStatus.UNREAD) {
                                msg.copy(status = InboxMessageStatus.READ)
                            } else {
                                msg
                            }
                        }.toMutableList()
                        unreadCount = 0
                        saveCachedData()

                        RiviumPushExecutors.executeMain {
                            onSuccess?.invoke()
                        }
                    } else {
                        Log.e(TAG, "Mark all read failed: ${it.code}")
                        RiviumPushExecutors.executeMain {
                            onError?.invoke("Failed to mark all as read: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mark all read error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Get the unread count (from cache).
     */
    fun getUnreadCount(): Int = unreadCount

    /**
     * Get the unread count from server.
     */
    fun fetchUnreadCount(
        onSuccess: (Int) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        getMessages(
            filter = InboxFilter(status = InboxMessageStatus.UNREAD, limit = 1),
            onSuccess = { response ->
                unreadCount = response.unreadCount
                saveCachedData()
                onSuccess(response.unreadCount)
            },
            onError = { error ->
                onError?.invoke(error)
            }
        )
    }

    /**
     * Get cached messages without network call.
     */
    fun getCachedMessages(): List<InboxMessage> = cachedMessages.toList()

    /**
     * Handle incoming inbox message from pn-protocol.
     */
    fun handleIncomingMessage(message: InboxMessage) {
        Log.d(TAG, "Received new inbox message: ${message.id}")

        // Add to cache
        cachedMessages.add(0, message)
        if (message.status == InboxMessageStatus.UNREAD) {
            unreadCount++
        }
        saveCachedData()

        // Notify callback
        RiviumPushExecutors.executeMain {
            callback?.onMessageReceived(message)
        }
    }

    private fun updateMessageStatus(
        messageId: String,
        status: InboxMessageStatus,
        onSuccess: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val params = mapOf("status" to status.name.lowercase())
                val body = gson.toJson(params).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${RiviumPushConfig.SERVER_URL}/inbox/messages/$messageId")
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .put(body)
                    .build()

                Log.d(TAG, "Updating message $messageId status to ${status.name}")

                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        // Update local cache
                        val index = cachedMessages.indexOfFirst { msg -> msg.id == messageId }
                        if (index >= 0) {
                            val oldStatus = cachedMessages[index].status
                            cachedMessages[index] = cachedMessages[index].copy(status = status)

                            // Update unread count
                            if (oldStatus == InboxMessageStatus.UNREAD && status != InboxMessageStatus.UNREAD) {
                                unreadCount = maxOf(0, unreadCount - 1)
                            }
                        }
                        saveCachedData()

                        RiviumPushExecutors.executeMain {
                            callback?.onMessageStatusChanged(messageId, status)
                            onSuccess?.invoke()
                        }
                    } else {
                        Log.e(TAG, "Update status failed: ${it.code}")
                        RiviumPushExecutors.executeMain {
                            onError?.invoke("Failed to update message status: ${it.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update status error: ${e.message}")
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun parseMessagesResponse(json: String): InboxMessagesResponse {
        return try {
            // Try parsing as a response object first
            gson.fromJson(json, InboxMessagesResponse::class.java)
        } catch (e: Exception) {
            // If that fails, try parsing as an array
            try {
                val type = object : TypeToken<List<InboxMessage>>() {}.type
                val messages: List<InboxMessage> = gson.fromJson(json, type)
                val unread = messages.count { it.status == InboxMessageStatus.UNREAD }
                InboxMessagesResponse(messages, messages.size, unread)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse messages response: ${e2.message}")
                InboxMessagesResponse(emptyList(), 0, 0)
            }
        }
    }

    private fun loadCachedData() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val messagesJson = prefs.getString(KEY_CACHED_MESSAGES, null)
            if (messagesJson != null) {
                val type = object : TypeToken<List<InboxMessage>>() {}.type
                cachedMessages = gson.fromJson(messagesJson, type)
            }
            unreadCount = prefs.getInt(KEY_UNREAD_COUNT, 0)
            Log.d(TAG, "Loaded ${cachedMessages.size} cached messages, unread: $unreadCount")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached data: ${e.message}")
        }
    }

    private fun saveCachedData() {
        // Capture current state for background write
        val messagesToSave = cachedMessages.toList()
        val countToSave = unreadCount

        // Write to SharedPreferences on IO thread to avoid ANR
        RiviumPushExecutors.executeIO {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CACHED_MESSAGES, gson.toJson(messagesToSave))
                    .putInt(KEY_UNREAD_COUNT, countToSave)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save cached data: ${e.message}")
            }
        }
    }

    /**
     * Clear all cached data.
     */
    fun clearCache() {
        cachedMessages.clear()
        unreadCount = 0
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
        Log.d(TAG, "Cache cleared")
    }
}
