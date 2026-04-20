package co.rivium.push.sdk.abtesting

import org.json.JSONObject

/**
 * Represents an A/B test variant assignment
 */
data class ABTestVariant(
    val testId: String,
    val variantId: String,
    val variantName: String,
    val isControlGroup: Boolean = false,
    val content: ABTestContent? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestVariant {
            val content = if (json.has("content") && !json.isNull("content")) {
                ABTestContent.fromJson(json.getJSONObject("content"))
            } else null

            return ABTestVariant(
                testId = json.getString("testId"),
                variantId = json.getString("variantId"),
                variantName = json.getString("variantName"),
                isControlGroup = json.optBoolean("isControlGroup", false),
                content = content
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("testId", testId)
            put("variantId", variantId)
            put("variantName", variantName)
            put("isControlGroup", isControlGroup)
            content?.let { put("content", it.toJson()) }
        }
    }
}

/**
 * Content for an A/B test variant
 */
data class ABTestContent(
    val title: String,
    val body: String,
    val data: Map<String, Any>? = null,
    val imageUrl: String? = null,
    val deepLink: String? = null,
    val actions: List<ABTestAction>? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestContent {
            val data = if (json.has("data") && !json.isNull("data")) {
                val dataJson = json.getJSONObject("data")
                val map = mutableMapOf<String, Any>()
                dataJson.keys().forEach { key ->
                    map[key] = dataJson.get(key)
                }
                map
            } else null

            val actions = if (json.has("actions") && !json.isNull("actions")) {
                val actionsArray = json.getJSONArray("actions")
                (0 until actionsArray.length()).map { i ->
                    ABTestAction.fromJson(actionsArray.getJSONObject(i))
                }
            } else null

            return ABTestContent(
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                data = data,
                imageUrl = if (json.has("imageUrl") && !json.isNull("imageUrl")) json.getString("imageUrl") else null,
                deepLink = if (json.has("deepLink") && !json.isNull("deepLink")) json.getString("deepLink") else null,
                actions = actions
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("title", title)
            put("body", body)
            data?.let { put("data", JSONObject(it)) }
            imageUrl?.let { put("imageUrl", it) }
            deepLink?.let { put("deepLink", it) }
            actions?.let { actionList ->
                val actionsArray = org.json.JSONArray()
                actionList.forEach { actionsArray.put(it.toJson()) }
                put("actions", actionsArray)
            }
        }
    }
}

/**
 * Action button for A/B test variant
 */
data class ABTestAction(
    val id: String,
    val title: String,
    val action: String
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestAction {
            return ABTestAction(
                id = json.getString("id"),
                title = json.getString("title"),
                action = json.getString("action")
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("action", action)
        }
    }
}

/**
 * Summary of an active A/B test
 */
data class ABTestSummary(
    val id: String,
    val name: String,
    val variantCount: Int,
    val hasControlGroup: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestSummary {
            return ABTestSummary(
                id = json.getString("id"),
                name = json.getString("name"),
                variantCount = json.optInt("variantCount", 0),
                hasControlGroup = json.optBoolean("hasControlGroup", false)
            )
        }
    }
}

/**
 * Statistical results for an A/B test
 */
data class ABTestStatistics(
    val isSignificant: Boolean,
    val confidenceLevel: Double,
    val pValue: Double,
    val lift: Double,
    val sampleSizeRecommendation: Int? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestStatistics {
            return ABTestStatistics(
                isSignificant = json.getBoolean("isSignificant"),
                confidenceLevel = json.getDouble("confidenceLevel"),
                pValue = json.getDouble("pValue"),
                lift = json.getDouble("lift"),
                sampleSizeRecommendation = if (json.has("sampleSizeRecommendation")) {
                    json.getInt("sampleSizeRecommendation")
                } else null
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("isSignificant", isSignificant)
            put("confidenceLevel", confidenceLevel)
            put("pValue", pValue)
            put("lift", lift)
            sampleSizeRecommendation?.let { put("sampleSizeRecommendation", it) }
        }
    }
}

/**
 * Confidence interval for a metric
 */
data class ConfidenceInterval(
    val lower: Double,
    val upper: Double
) {
    companion object {
        fun fromJson(json: JSONObject): ConfidenceInterval {
            return ConfidenceInterval(
                lower = json.getDouble("lower"),
                upper = json.getDouble("upper")
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("lower", lower)
            put("upper", upper)
        }
    }
}

/**
 * Variant statistics with confidence intervals
 */
data class ABTestVariantStats(
    val id: String,
    val name: String,
    val isControlGroup: Boolean,
    val trafficPercentage: Int,
    val sentCount: Int,
    val deliveredCount: Int,
    val openedCount: Int,
    val clickedCount: Int,
    val convertedCount: Int,
    val failedCount: Int,
    val deliveryRate: Double,
    val openRate: Double,
    val clickRate: Double,
    val conversionRate: Double,
    val confidenceInterval: ConfidenceInterval? = null,
    val improvementVsControl: Double? = null,
    val isSignificantVsControl: Boolean? = null,
    val pValueVsControl: Double? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ABTestVariantStats {
            return ABTestVariantStats(
                id = json.getString("id"),
                name = json.getString("name"),
                isControlGroup = json.optBoolean("isControlGroup", false),
                trafficPercentage = json.getInt("trafficPercentage"),
                sentCount = json.getInt("sentCount"),
                deliveredCount = json.getInt("deliveredCount"),
                openedCount = json.getInt("openedCount"),
                clickedCount = json.getInt("clickedCount"),
                convertedCount = json.optInt("convertedCount", 0),
                failedCount = json.getInt("failedCount"),
                deliveryRate = json.getDouble("deliveryRate"),
                openRate = json.getDouble("openRate"),
                clickRate = json.getDouble("clickRate"),
                conversionRate = json.optDouble("conversionRate", 0.0),
                confidenceInterval = if (json.has("confidenceInterval") && !json.isNull("confidenceInterval")) {
                    ConfidenceInterval.fromJson(json.getJSONObject("confidenceInterval"))
                } else null,
                improvementVsControl = if (json.has("improvementVsControl")) {
                    json.getDouble("improvementVsControl")
                } else null,
                isSignificantVsControl = if (json.has("isSignificantVsControl")) {
                    json.getBoolean("isSignificantVsControl")
                } else null,
                pValueVsControl = if (json.has("pValueVsControl")) {
                    json.getDouble("pValueVsControl")
                } else null
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("isControlGroup", isControlGroup)
            put("trafficPercentage", trafficPercentage)
            put("sentCount", sentCount)
            put("deliveredCount", deliveredCount)
            put("openedCount", openedCount)
            put("clickedCount", clickedCount)
            put("convertedCount", convertedCount)
            put("failedCount", failedCount)
            put("deliveryRate", deliveryRate)
            put("openRate", openRate)
            put("clickRate", clickRate)
            put("conversionRate", conversionRate)
            confidenceInterval?.let { put("confidenceInterval", it.toJson()) }
            improvementVsControl?.let { put("improvementVsControl", it) }
            isSignificantVsControl?.let { put("isSignificantVsControl", it) }
            pValueVsControl?.let { put("pValueVsControl", it) }
        }
    }
}

/**
 * Tracking event types for A/B tests
 */
enum class ABTestEvent(val value: String) {
    IMPRESSION("impression"),
    OPENED("opened"),
    CLICKED("clicked"),
    CONVERTED("converted")
}
