package co.rivium.push.sdk

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * API client for communicating with Rivium Push backend server.
 * Includes automatic retry logic with exponential backoff.
 */
class ApiClient(private val config: RiviumPushConfig) {
    companion object {
        private const val TAG = "Api"
    }

    // Use secure client with retry interceptor and optional certificate pinning
    private val client = NetworkConfig.createSecureClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class RegisterRequest(
        val deviceId: String,
        val platform: String = "android",
        val userId: String? = null,
        val metadata: Map<String, Any>? = null,
        val appIdentifier: String? = null
    )

    data class PNConnectionConfig(
        val host: String,
        val wsHost: String? = null,
        val port: Int = 8883,
        val wsPort: Int = 443,
        val token: String? = null, // JWT token for PN Protocol authentication
        val secure: Boolean = true // Enable TLS/SSL for secure connection
    )

    data class RegisterResponse(
        val deviceId: String,
        val appId: String? = null, // App ID from server (first 16 chars of projectId)
        val appIdentifier: String? = null, // App identifier for per-app message routing
        val message: String? = null,
        val mqtt: PNConnectionConfig? = null // Server returns 'mqtt' key (legacy name), mapped to PNConnectionConfig
    )

    interface ApiCallback<T> {
        fun onSuccess(response: T)
        fun onError(error: String)
    }

    /**
     * Register device with the server.
     */
    fun registerDevice(
        deviceId: String,
        userId: String? = null,
        metadata: Map<String, Any>? = null,
        appIdentifier: String? = null,
        callback: ApiCallback<RegisterResponse>
    ) {
        val request = RegisterRequest(
            deviceId = deviceId,
            userId = userId,
            metadata = metadata,
            appIdentifier = appIdentifier
        )

        val body = gson.toJson(request).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/register")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Registering device: $deviceId")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Registration failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // Read response data before closing - must be done on callback thread
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        Log.d(TAG, "Device registered successfully: $responseBody")
                        try {
                            val jsonResponse = gson.fromJson(responseBody, RegisterResponse::class.java)
                            callback.onSuccess(jsonResponse ?: RegisterResponse(deviceId))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse registration response: ${e.message}")
                            callback.onSuccess(RegisterResponse(deviceId))
                        }
                    } else {
                        Log.e(TAG, "Registration failed: $code - $responseBody")
                        callback.onError("Registration failed: $code")
                    }
                }
            }
        })
    }

    /**
     * Subscribe to a topic.
     */
    fun subscribeTopic(deviceId: String, topic: String, callback: ApiCallback<String>) {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/$deviceId/topics/$topic")
            .addHeader("x-api-key", config.apiKey)
            .post("".toRequestBody(null))
            .build()

        Log.d(TAG, "Subscribing to topic: $topic")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Topic subscription failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        Log.d(TAG, "Subscribed to topic '$topic' successfully")
                        callback.onSuccess(responseBody)
                    } else {
                        Log.e(TAG, "Topic subscription failed: $code")
                        callback.onError("Topic subscription failed: $code")
                    }
                }
            }
        })
    }

    /**
     * Unsubscribe from a topic.
     */
    fun unsubscribeTopic(deviceId: String, topic: String, callback: ApiCallback<String>) {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/$deviceId/topics/$topic")
            .addHeader("x-api-key", config.apiKey)
            .delete()
            .build()

        Log.d(TAG, "Unsubscribing from topic: $topic")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Topic unsubscription failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        Log.d(TAG, "Unsubscribed from topic '$topic' successfully")
                        callback.onSuccess(responseBody)
                    } else {
                        Log.e(TAG, "Topic unsubscription failed: $code")
                        callback.onError("Topic unsubscription failed: $code")
                    }
                }
            }
        })
    }

    /**
     * Set user ID for a device.
     */
    fun setUserId(deviceId: String, userId: String, callback: ApiCallback<String>) {
        val body = gson.toJson(mapOf("userId" to userId)).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/$deviceId/user")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Setting user ID: $userId for device: $deviceId")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Set user ID failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        Log.d(TAG, "User ID set successfully")
                        callback.onSuccess(responseBody)
                    } else {
                        Log.e(TAG, "Set user ID failed: $code")
                        callback.onError("Set user ID failed: $code")
                    }
                }
            }
        })
    }

    /**
     * Clear user ID for a device.
     */
    fun clearUserId(deviceId: String, callback: ApiCallback<String>) {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/$deviceId/user")
            .addHeader("x-api-key", config.apiKey)
            .delete()
            .build()

        Log.d(TAG, "Clearing user ID for device: $deviceId")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Clear user ID failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        Log.d(TAG, "User ID cleared successfully")
                        callback.onSuccess(responseBody)
                    } else {
                        Log.e(TAG, "Clear user ID failed: $code")
                        callback.onError("Clear user ID failed: $code")
                    }
                }
            }
        })
    }

    // ==================== Push Server Configuration ====================

    data class PushConfig(
        val host: String,
        val port: Int = 1883,
        val username: String? = null,
        val password: String? = null
    )

    // Internal response mapping - server returns "mqtt" field
    private data class PushConfigResponse(
        val mqtt: PushConfig
    )

    /**
     * Fetch push server configuration from server (synchronous - call from background thread).
     */
    fun fetchPushConfig(): PushConfig? {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/config")
            .addHeader("x-api-key", config.apiKey)
            .get()
            .build()

        Log.d(TAG, "Fetching push config from server")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val responseBody = it.body?.string()
                    if (responseBody != null) {
                        val configResponse = gson.fromJson(responseBody, PushConfigResponse::class.java)
                        Log.d(TAG, "Fetched push config: host=${configResponse.mqtt.host}, port=${configResponse.mqtt.port}, user=${configResponse.mqtt.username}")
                        configResponse.mqtt
                    } else {
                        Log.e(TAG, "Empty response from config endpoint")
                        null
                    }
                } else {
                    Log.e(TAG, "Fetch push config failed: ${it.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch push config failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch push server configuration from server (async).
     */
    fun fetchPushConfigAsync(callback: ApiCallback<PushConfig>) {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/devices/config")
            .addHeader("x-api-key", config.apiKey)
            .get()
            .build()

        Log.d(TAG, "Fetching push config from server (async)")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Fetch push config failed: ${e.message}")
                mainHandler.post {
                    callback.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // Read response body before closing - must be done on callback thread
                val isSuccessful = response.isSuccessful
                val code = response.code
                val responseBody = response.body?.string()
                response.close()

                mainHandler.post {
                    if (isSuccessful) {
                        if (responseBody != null) {
                            try {
                                val configResponse = gson.fromJson(responseBody, PushConfigResponse::class.java)
                                Log.d(TAG, "Fetched push config: host=${configResponse.mqtt.host}, port=${configResponse.mqtt.port}, user=${configResponse.mqtt.username}")
                                callback.onSuccess(configResponse.mqtt)
                            } catch (e: Exception) {
                                Log.e(TAG, "Parse push config failed: ${e.message}")
                                callback.onError("Failed to parse push config")
                            }
                        } else {
                            callback.onError("Empty response from server")
                        }
                    } else {
                        Log.e(TAG, "Fetch push config failed: $code")
                        callback.onError("Failed to fetch push config: $code")
                    }
                }
            }
        })
    }

    // ==================== In-App Messages ====================

    /**
     * Fetch in-app messages for a device (synchronous - call from background thread).
     * Uses POST /in-app/fetch endpoint with body params.
     */
    fun getInAppMessages(params: Map<String, Any?>): String? {
        val body = gson.toJson(params).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/in-app/fetch")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Fetching in-app messages for device: ${params["deviceId"]}")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Fetched in-app messages successfully")
                    responseBody
                } else {
                    Log.e(TAG, "Fetch in-app messages failed: ${it.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Fetch in-app messages failed: ${e.message}")
            null
        }
    }

    /**
     * Record an in-app message impression (synchronous - call from background thread).
     * Uses POST /in-app/impression endpoint.
     */
    fun recordInAppImpression(params: Map<String, String>): Boolean {
        val body = gson.toJson(params).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/in-app/impression")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Recording in-app impression: ${params["action"]} for message: ${params["messageId"]}")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "In-app impression recorded successfully")
                    true
                } else {
                    Log.e(TAG, "Record impression failed: ${it.code}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Record impression failed: ${e.message}")
            false
        }
    }

    // ==================== A/B Testing ====================

    /**
     * Get all active A/B tests for the app (synchronous - call from background thread).
     * Uses GET /ab-tests/sdk/active endpoint.
     */
    fun getActiveABTests(): String? {
        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/ab-tests/sdk/active")
            .addHeader("x-api-key", config.apiKey)
            .get()
            .build()

        Log.d(TAG, "Fetching active A/B tests")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Fetched active A/B tests successfully")
                    responseBody
                } else {
                    Log.e(TAG, "Fetch active A/B tests failed: ${it.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Fetch active A/B tests failed: ${e.message}")
            null
        }
    }

    /**
     * Get A/B test variant assignment for a device (synchronous - call from background thread).
     * Uses POST /ab-tests/sdk/assignment endpoint.
     */
    fun getABTestAssignment(testId: String, deviceId: String): String? {
        val params = mapOf("testId" to testId, "deviceId" to deviceId)
        val body = gson.toJson(params).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/ab-tests/sdk/assignment")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Fetching A/B test assignment for test: $testId, device: $deviceId")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Fetched A/B test assignment successfully")
                    responseBody
                } else {
                    Log.e(TAG, "Fetch A/B test assignment failed: ${it.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Fetch A/B test assignment failed: ${e.message}")
            null
        }
    }

    /**
     * Track an A/B test event (synchronous - call from background thread).
     * Uses POST /ab-tests/sdk/track/{event} endpoint.
     */
    fun trackABTestEvent(testId: String, variantId: String, deviceId: String, event: String): Boolean {
        val params = mapOf("testId" to testId, "variantId" to variantId, "deviceId" to deviceId)
        val body = gson.toJson(params).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("${RiviumPushConfig.SERVER_URL}/ab-tests/sdk/track/$event")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.d(TAG, "Tracking A/B test event: $event for test: $testId, variant: $variantId")

        return try {
            val response = client.newCall(httpRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "A/B test event tracked successfully")
                    true
                } else {
                    Log.e(TAG, "Track A/B test event failed: ${it.code}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Track A/B test event failed: ${e.message}")
            false
        }
    }
}
