package co.rivium.push.sdk.abtesting

import android.content.Context
import android.content.SharedPreferences
import co.rivium.push.sdk.ApiClient
import co.rivium.push.sdk.RiviumPushExecutors
import co.rivium.push.sdk.RiviumPushLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Callback interface for A/B testing events
 */
interface ABTestingCallback {
    /**
     * Called when variant is assigned for a test
     */
    fun onVariantAssigned(variant: ABTestVariant)

    /**
     * Called when there's an error
     */
    fun onError(testId: String?, error: String)
}

/**
 * Manages A/B tests: fetching assignments, caching, and tracking
 */
class ABTestingManager private constructor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val deviceId: String
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cached variant assignments (testId -> variant)
    private val cachedAssignments: MutableMap<String, ABTestVariant> = ConcurrentHashMap()

    private var callback: ABTestingCallback? = null

    companion object {
        private const val TAG = "RiviumPush.ABTesting"
        private const val PREFS_NAME = "rivium_push_abtesting"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_LAST_FETCH = "last_fetch"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

        @Volatile
        private var instance: ABTestingManager? = null

        fun getInstance(
            context: Context,
            apiClient: ApiClient,
            deviceId: String
        ): ABTestingManager {
            return instance ?: synchronized(this) {
                instance ?: ABTestingManager(context, apiClient, deviceId).also {
                    instance = it
                }
            }
        }

        fun getInstance(): ABTestingManager? = instance
    }

    init {
        loadCachedAssignments()
    }

    /**
     * Set callback for A/B testing events
     */
    fun setCallback(callback: ABTestingCallback?) {
        this.callback = callback
    }

    /**
     * Get all active A/B tests for the app
     */
    fun getActiveTests(onSuccess: (List<ABTestSummary>) -> Unit, onError: ((String) -> Unit)? = null) {
        RiviumPushExecutors.executeNetwork {
            try {
                val response = apiClient.getActiveABTests()
                if (response != null) {
                    val tests = parseActiveTests(response)
                    RiviumPushLogger.d(TAG, "Found ${tests.size} active A/B tests")
                    RiviumPushExecutors.executeMain {
                        onSuccess(tests)
                    }
                } else {
                    RiviumPushLogger.w(TAG, "No response from active tests endpoint")
                    RiviumPushExecutors.executeMain {
                        onSuccess(emptyList())
                    }
                }
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Failed to get active tests", e)
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Get variant assignment for a specific test
     * Returns cached assignment if available, otherwise fetches from server
     */
    fun getVariant(
        testId: String,
        forceRefresh: Boolean = false,
        onSuccess: (ABTestVariant) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        // Check cache first
        if (!forceRefresh) {
            val cached = cachedAssignments[testId]
            if (cached != null && !isCacheExpired()) {
                RiviumPushLogger.d(TAG, "Returning cached variant for test $testId: ${cached.variantName}")
                onSuccess(cached)
                return
            }
        }

        // Fetch from server
        RiviumPushExecutors.executeNetwork {
            try {
                val response = apiClient.getABTestAssignment(testId, deviceId)
                if (response != null) {
                    val variant = ABTestVariant.fromJson(JSONObject(response))

                    // Cache the assignment
                    cachedAssignments[testId] = variant
                    RiviumPushExecutors.executeIO {
                        saveCachedAssignments()
                    }

                    RiviumPushLogger.d(TAG, "Got variant ${variant.variantName} for test $testId")

                    RiviumPushExecutors.executeMain {
                        callback?.onVariantAssigned(variant)
                        onSuccess(variant)
                    }
                } else {
                    RiviumPushLogger.w(TAG, "No response from assignment endpoint")
                    RiviumPushExecutors.executeMain {
                        onError?.invoke("Failed to get variant assignment")
                        callback?.onError(testId, "Failed to get variant assignment")
                    }
                }
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Failed to get variant for test $testId", e)
                RiviumPushExecutors.executeMain {
                    val error = e.message ?: "Unknown error"
                    onError?.invoke(error)
                    callback?.onError(testId, error)
                }
            }
        }
    }

    /**
     * Get cached variant for a test (synchronous, no network call)
     */
    fun getCachedVariant(testId: String): ABTestVariant? {
        return cachedAssignments[testId]
    }

    /**
     * Track an event for an A/B test
     */
    fun trackEvent(
        testId: String,
        variantId: String,
        event: ABTestEvent,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        RiviumPushExecutors.executeNetwork {
            try {
                val success = apiClient.trackABTestEvent(testId, variantId, deviceId, event.value)
                RiviumPushExecutors.executeMain {
                    if (success) {
                        RiviumPushLogger.d(TAG, "Tracked ${event.value} for test $testId, variant $variantId")
                        onSuccess?.invoke()
                    } else {
                        onError?.invoke("Failed to track event")
                    }
                }
            } catch (e: Exception) {
                RiviumPushLogger.e(TAG, "Failed to track event", e)
                RiviumPushExecutors.executeMain {
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Track impression (variant was shown to user)
     */
    fun trackImpression(testId: String, variantId: String, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        trackEvent(testId, variantId, ABTestEvent.IMPRESSION, onSuccess, onError)
    }

    /**
     * Track opened (user opened/viewed the content)
     */
    fun trackOpened(testId: String, variantId: String, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        trackEvent(testId, variantId, ABTestEvent.OPENED, onSuccess, onError)
    }

    /**
     * Track clicked (user clicked a CTA)
     */
    fun trackClicked(testId: String, variantId: String, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        trackEvent(testId, variantId, ABTestEvent.CLICKED, onSuccess, onError)
    }

    /**
     * Track converted (user completed desired action like purchase, signup, etc.)
     */
    fun trackConverted(testId: String, variantId: String, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        trackEvent(testId, variantId, ABTestEvent.CONVERTED, onSuccess, onError)
    }

    /**
     * Track conversion using cached variant
     */
    fun trackConversion(testId: String, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val variant = cachedAssignments[testId]
        if (variant != null) {
            trackConverted(testId, variant.variantId, onSuccess, onError)
        } else {
            onError?.invoke("Variant not found for test $testId")
        }
    }

    /**
     * Check if device is in control group for a test
     */
    fun isInControlGroup(testId: String): Boolean {
        return cachedAssignments[testId]?.isControlGroup ?: false
    }

    /**
     * Track impression and auto-call opened when variant content is displayed
     */
    fun trackDisplay(variant: ABTestVariant, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        trackImpression(variant.testId, variant.variantId, {
            trackOpened(variant.testId, variant.variantId, onSuccess, onError)
        }, onError)
    }

    /**
     * Clear all cached assignments
     */
    fun clearCache() {
        cachedAssignments.clear()
        RiviumPushExecutors.executeIO {
            prefs.edit().clear().apply()
        }
        RiviumPushLogger.d(TAG, "Cache cleared")
    }

    // ==================== Private Methods ====================

    private fun isCacheExpired(): Boolean {
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch > CACHE_TTL_MS
    }

    private fun parseActiveTests(json: String): List<ABTestSummary> {
        val tests = mutableListOf<ABTestSummary>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                tests.add(ABTestSummary.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to parse active tests", e)
        }
        return tests
    }

    private fun loadCachedAssignments() {
        try {
            val json = prefs.getString(KEY_ASSIGNMENTS, null) ?: return
            val obj = JSONObject(json)
            obj.keys().forEach { testId ->
                val variantJson = obj.getJSONObject(testId)
                cachedAssignments[testId] = ABTestVariant.fromJson(variantJson)
            }
            RiviumPushLogger.d(TAG, "Loaded ${cachedAssignments.size} cached assignments")
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to load cached assignments", e)
        }
    }

    private fun saveCachedAssignments() {
        try {
            val obj = JSONObject()
            cachedAssignments.forEach { (testId, variant) ->
                obj.put(testId, variant.toJson())
            }
            prefs.edit()
                .putString(KEY_ASSIGNMENTS, obj.toString())
                .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            RiviumPushLogger.e(TAG, "Failed to save cached assignments", e)
        }
    }
}
