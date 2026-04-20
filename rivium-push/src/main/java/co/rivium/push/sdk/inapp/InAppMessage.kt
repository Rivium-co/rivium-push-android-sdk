package co.rivium.push.sdk.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * In-App Message types
 */
enum class InAppMessageType(val value: String) {
    MODAL("modal"),
    BANNER("banner"),
    FULLSCREEN("fullscreen"),
    CARD("card");

    companion object {
        fun fromString(value: String): InAppMessageType {
            return values().find { it.value == value } ?: MODAL
        }
    }
}

/**
 * In-App Message trigger types
 */
enum class InAppTriggerType(val value: String) {
    ON_APP_OPEN("on_app_open"),
    ON_EVENT("on_event"),
    ON_SESSION_START("on_session_start"),
    SCHEDULED("scheduled"),
    MANUAL("manual");

    companion object {
        fun fromString(value: String): InAppTriggerType {
            return values().find { it.value == value } ?: ON_APP_OPEN
        }
    }
}

/**
 * Button style for in-app message buttons
 */
enum class InAppButtonStyle(val value: String) {
    PRIMARY("primary"),
    SECONDARY("secondary"),
    TEXT("text"),
    DESTRUCTIVE("destructive");

    companion object {
        fun fromString(value: String): InAppButtonStyle {
            return values().find { it.value == value } ?: PRIMARY
        }
    }
}

/**
 * Button action type
 */
enum class InAppButtonAction(val value: String) {
    DISMISS("dismiss"),
    DEEP_LINK("deep_link"),
    URL("url"),
    CUSTOM("custom");

    companion object {
        fun fromString(value: String): InAppButtonAction {
            return values().find { it.value == value } ?: DISMISS
        }
    }
}

/**
 * In-App Message button
 */
data class InAppButton(
    val id: String,
    val text: String,
    val action: InAppButtonAction,
    val value: String? = null,
    val style: InAppButtonStyle = InAppButtonStyle.PRIMARY
) {
    companion object {
        fun fromJson(json: JSONObject): InAppButton {
            return InAppButton(
                id = json.optString("id", ""),
                text = json.optString("text", ""),
                action = InAppButtonAction.fromString(json.optString("action", "dismiss")),
                value = if (json.has("value") && !json.isNull("value")) json.getString("value") else null,
                style = InAppButtonStyle.fromString(json.optString("style", "primary"))
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("text", text)
            put("action", action.value)
            value?.let { put("value", it) }
            put("style", style.value)
        }
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "text" to text,
            "action" to action.value,
            "value" to value,
            "style" to style.value
        )
    }
}

/**
 * In-App Message content
 */
data class InAppMessageContent(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val buttons: List<InAppButton> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): InAppMessageContent {
            val buttons = mutableListOf<InAppButton>()
            json.optJSONArray("buttons")?.let { array ->
                for (i in 0 until array.length()) {
                    buttons.add(InAppButton.fromJson(array.getJSONObject(i)))
                }
            }

            return InAppMessageContent(
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                imageUrl = if (json.has("imageUrl") && !json.isNull("imageUrl")) json.getString("imageUrl") else null,
                backgroundColor = if (json.has("backgroundColor") && !json.isNull("backgroundColor")) json.getString("backgroundColor") else null,
                textColor = if (json.has("textColor") && !json.isNull("textColor")) json.getString("textColor") else null,
                buttons = buttons
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("title", title)
            put("body", body)
            imageUrl?.let { put("imageUrl", it) }
            backgroundColor?.let { put("backgroundColor", it) }
            textColor?.let { put("textColor", it) }
            if (buttons.isNotEmpty()) {
                put("buttons", JSONArray().apply {
                    buttons.forEach { put(it.toJson()) }
                })
            }
        }
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "title" to title,
            "body" to body,
            "imageUrl" to imageUrl,
            "backgroundColor" to backgroundColor,
            "textColor" to textColor,
            "buttons" to buttons.map { it.toMap() }
        )
    }
}

/**
 * Localized content for in-app messages
 */
data class LocalizedContent(
    val locale: String,
    val content: InAppMessageContent
) {
    companion object {
        fun fromJson(json: JSONObject): LocalizedContent {
            return LocalizedContent(
                locale = json.optString("locale", ""),
                content = InAppMessageContent.fromJson(json.getJSONObject("content"))
            )
        }
    }
}

/**
 * In-App Message
 */
data class InAppMessage(
    val id: String,
    val name: String,
    val type: InAppMessageType,
    val content: InAppMessageContent,
    val localizations: List<LocalizedContent> = emptyList(),
    val triggerType: InAppTriggerType,
    val triggerEvent: String? = null,
    val triggerConditions: Map<String, Any>? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val maxImpressions: Int = 1,
    val minSessionCount: Int = 0,
    val delaySeconds: Int = 0,
    val priority: Int = 0
) {
    companion object {
        // ISO 8601 date format for parsing backend dates
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val isoDateFormatAlt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Parse a date string (ISO 8601) or epoch milliseconds to Long
         */
        private fun parseDate(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null

            // Try to get as Long first (epoch milliseconds)
            val longValue = json.optLong(key, -1)
            if (longValue > 0) return longValue

            // Try to parse as ISO 8601 date string
            val dateString = if (json.has(key) && !json.isNull(key)) json.getString(key) else return null
            return try {
                isoDateFormat.parse(dateString)?.time
            } catch (e: Exception) {
                try {
                    isoDateFormatAlt.parse(dateString)?.time
                } catch (e2: Exception) {
                    null
                }
            }
        }

        fun fromJson(json: JSONObject): InAppMessage {
            val localizations = mutableListOf<LocalizedContent>()
            json.optJSONArray("localizations")?.let { array ->
                for (i in 0 until array.length()) {
                    localizations.add(LocalizedContent.fromJson(array.getJSONObject(i)))
                }
            }

            return InAppMessage(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                type = InAppMessageType.fromString(json.optString("type", "modal")),
                content = InAppMessageContent.fromJson(json.getJSONObject("content")),
                localizations = localizations,
                triggerType = InAppTriggerType.fromString(json.optString("triggerType", "on_app_open")),
                triggerEvent = if (json.has("triggerEvent") && !json.isNull("triggerEvent")) json.getString("triggerEvent") else null,
                triggerConditions = null, // Parse if needed
                startDate = parseDate(json, "startDate"),
                endDate = parseDate(json, "endDate"),
                maxImpressions = json.optInt("maxImpressions", 1),
                minSessionCount = json.optInt("minSessionCount", 0),
                delaySeconds = json.optInt("delaySeconds", 0),
                priority = json.optInt("priority", 0)
            )
        }
    }

    /**
     * Get localized content for the device's locale
     */
    fun getLocalizedContent(locale: String): InAppMessageContent {
        val deviceLocale = locale.lowercase().split("-", "_").firstOrNull() ?: "en"

        // Find exact match first
        localizations.find { it.locale.lowercase() == locale.lowercase() }?.let {
            return it.content
        }

        // Find language match
        localizations.find { it.locale.lowercase().startsWith(deviceLocale) }?.let {
            return it.content
        }

        // Fall back to default content
        return content
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type.value)
            put("content", content.toJson())
            put("triggerType", triggerType.value)
            triggerEvent?.let { put("triggerEvent", it) }
            startDate?.let { put("startDate", it) }
            endDate?.let { put("endDate", it) }
            put("maxImpressions", maxImpressions)
            put("minSessionCount", minSessionCount)
            put("delaySeconds", delaySeconds)
            put("priority", priority)
        }
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "type" to type.value,
            "content" to content.toMap(),
            "triggerType" to triggerType.value,
            "triggerEvent" to triggerEvent,
            "startDate" to startDate,
            "endDate" to endDate,
            "maxImpressions" to maxImpressions,
            "minSessionCount" to minSessionCount,
            "delaySeconds" to delaySeconds,
            "priority" to priority
        )
    }
}

/**
 * Impression action types
 */
enum class InAppImpressionAction(val value: String) {
    IMPRESSION("impression"),
    CLICK("click"),
    DISMISS("dismiss"),
    BUTTON_CLICK("button_click");

    companion object {
        fun fromString(value: String): InAppImpressionAction {
            return values().find { it.value == value } ?: IMPRESSION
        }
    }
}
