package co.rivium.push.sdk.inbox

import com.google.gson.annotations.SerializedName

/**
 * Represents an inbox message.
 */
data class InboxMessage(
    val id: String,
    val userId: String? = null,
    val deviceId: String? = null,
    val content: InboxContent,
    val status: InboxMessageStatus = InboxMessageStatus.UNREAD,
    val category: String? = null,
    val expiresAt: String? = null,
    val readAt: String? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

/**
 * Content of an inbox message.
 */
data class InboxContent(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val iconUrl: String? = null,
    val deepLink: String? = null,
    val data: Map<String, Any>? = null
)

/**
 * Status of an inbox message.
 */
enum class InboxMessageStatus {
    @SerializedName("unread")
    UNREAD,
    @SerializedName("read")
    READ,
    @SerializedName("archived")
    ARCHIVED,
    @SerializedName("deleted")
    DELETED
}

/**
 * Filter options for fetching inbox messages.
 */
data class InboxFilter(
    val userId: String? = null,
    val deviceId: String? = null,
    val status: InboxMessageStatus? = null,
    val category: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val locale: String? = null
)

/**
 * Response from getInboxMessages API.
 */
data class InboxMessagesResponse(
    val messages: List<InboxMessage>,
    val total: Int,
    val unreadCount: Int
)

/**
 * Callback interface for inbox operations.
 */
interface InboxCallback {
    /**
     * Called when a new inbox message is received.
     */
    fun onMessageReceived(message: InboxMessage)

    /**
     * Called when inbox message status changes.
     */
    fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus)
}

/**
 * Adapter class for InboxCallback with default empty implementations.
 */
open class InboxCallbackAdapter : InboxCallback {
    override fun onMessageReceived(message: InboxMessage) {}
    override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) {}
}

/**
 * Extension to convert InboxMessage to Map for Flutter/RN bridge.
 */
fun InboxMessage.toMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "userId" to userId,
        "deviceId" to deviceId,
        "content" to mapOf(
            "title" to content.title,
            "body" to content.body,
            "imageUrl" to content.imageUrl,
            "iconUrl" to content.iconUrl,
            "deepLink" to content.deepLink,
            "data" to content.data
        ),
        "status" to status.name.lowercase(),
        "category" to category,
        "expiresAt" to expiresAt,
        "readAt" to readAt,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
