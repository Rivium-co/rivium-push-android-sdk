package co.rivium.push.sdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Action button for notifications
 */
data class NotificationAction(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("action")
    val action: String? = null,

    @SerializedName("icon")
    val icon: String? = null,

    @SerializedName("destructive")
    val destructive: Boolean = false,

    @SerializedName("authRequired")
    val authRequired: Boolean = false
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "title" to title,
            "action" to action,
            "icon" to icon,
            "destructive" to destructive,
            "authRequired" to authRequired
        )
    }
}

/**
 * Localized content for notifications
 */
data class LocalizedContent(
    @SerializedName("locale")
    val locale: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("body")
    val body: String
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "locale" to locale,
            "title" to title,
            "body" to body
        )
    }
}

/**
 * Push notification message with rich notification support
 */
data class RiviumPushMessage(
    @SerializedName("title")
    val title: String,

    @SerializedName("body")
    val body: String,

    @SerializedName("data")
    val data: Map<String, Any>? = null,

    @SerializedName("silent")
    val silent: Boolean = false,

    // Rich notification fields
    @SerializedName("imageUrl")
    val imageUrl: String? = null,

    @SerializedName("iconUrl")
    val iconUrl: String? = null,

    @SerializedName("actions")
    val actions: List<NotificationAction>? = null,

    @SerializedName("deepLink")
    val deepLink: String? = null,

    @SerializedName("badge")
    val badge: Int? = null,

    @SerializedName("badgeAction")
    val badgeAction: String? = null,

    @SerializedName("sound")
    val sound: String? = null,

    @SerializedName("threadId")
    val threadId: String? = null,

    @SerializedName("collapseKey")
    val collapseKey: String? = null,

    @SerializedName("category")
    val category: String? = null,

    @SerializedName("priority")
    val priority: String? = null,

    @SerializedName("ttl")
    val ttl: Int? = null,

    @SerializedName("localizations")
    val localizations: List<LocalizedContent>? = null,

    @SerializedName("timezone")
    val timezone: String? = null,

    @SerializedName("messageId")
    val messageId: String? = null,

    @SerializedName("campaignId")
    val campaignId: String? = null
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): RiviumPushMessage? {
            return try {
                gson.fromJson(json, RiviumPushMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): RiviumPushMessage? {
            return try {
                val actionsRaw = map["actions"] as? List<Map<String, Any?>>
                val actions = actionsRaw?.map { actionMap ->
                    NotificationAction(
                        id = actionMap["id"] as? String ?: "",
                        title = actionMap["title"] as? String ?: "",
                        action = actionMap["action"] as? String,
                        icon = actionMap["icon"] as? String,
                        destructive = actionMap["destructive"] as? Boolean ?: false,
                        authRequired = actionMap["authRequired"] as? Boolean ?: false
                    )
                }

                val localizationsRaw = map["localizations"] as? List<Map<String, Any?>>
                val localizations = localizationsRaw?.map { locMap ->
                    LocalizedContent(
                        locale = locMap["locale"] as? String ?: "",
                        title = locMap["title"] as? String ?: "",
                        body = locMap["body"] as? String ?: ""
                    )
                }

                RiviumPushMessage(
                    title = map["title"] as? String ?: "",
                    body = map["body"] as? String ?: "",
                    data = map["data"] as? Map<String, Any>,
                    silent = map["silent"] as? Boolean ?: false,
                    imageUrl = map["imageUrl"] as? String,
                    iconUrl = map["iconUrl"] as? String,
                    actions = actions,
                    deepLink = map["deepLink"] as? String,
                    badge = (map["badge"] as? Number)?.toInt(),
                    badgeAction = map["badgeAction"] as? String,
                    sound = map["sound"] as? String,
                    threadId = map["threadId"] as? String,
                    collapseKey = map["collapseKey"] as? String,
                    category = map["category"] as? String,
                    priority = map["priority"] as? String,
                    ttl = (map["ttl"] as? Number)?.toInt(),
                    localizations = localizations,
                    timezone = map["timezone"] as? String,
                    messageId = map["messageId"] as? String,
                    campaignId = map["campaignId"] as? String
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String = gson.toJson(this)

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "title" to title,
            "body" to body,
            "data" to data,
            "silent" to silent,
            "imageUrl" to imageUrl,
            "iconUrl" to iconUrl,
            "actions" to actions?.map { it.toMap() },
            "deepLink" to deepLink,
            "badge" to badge,
            "badgeAction" to badgeAction,
            "sound" to sound,
            "threadId" to threadId,
            "collapseKey" to collapseKey,
            "category" to category,
            "priority" to priority,
            "ttl" to ttl,
            "localizations" to localizations?.map { it.toMap() },
            "timezone" to timezone,
            "messageId" to messageId,
            "campaignId" to campaignId
        ).filterValues { it != null }
    }

    /**
     * Get the localized title for the given locale, or the default title
     */
    fun getLocalizedTitle(locale: String): String {
        return localizations?.find { it.locale == locale }?.title ?: title
    }

    /**
     * Get the localized body for the given locale, or the default body
     */
    fun getLocalizedBody(locale: String): String {
        return localizations?.find { it.locale == locale }?.body ?: body
    }
}
