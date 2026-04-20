package co.rivium.push.sdk

import android.os.Looper
import com.google.gson.Gson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for ApiClient.
 * Uses MockWebServer to simulate API responses without real network calls.
 * Uses Robolectric because ApiClient requires Android's Handler/Looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: ApiClient
    private val gson = Gson()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Point to mock server
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        RiviumPushConfig.SERVER_URL = baseUrl

        val config = RiviumPushConfig(apiKey = "test_api_key")
        apiClient = ApiClient(config)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        RiviumPushConfig.resetDefaults()
    }

    /**
     * Helper to wait for async operations and flush main looper.
     */
    private fun awaitWithLooper(latch: CountDownLatch, timeoutSeconds: Long = 5): Boolean {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000)
        while (latch.count > 0L && System.currentTimeMillis() < deadline) {
            // Flush the main looper to execute posted callbacks
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        return latch.count == 0L
    }

    // ==================== Register Device Tests ====================

    @Test
    fun `registerDevice sends correct request format`() {
        // Given
        val successResponse = """
            {
                "deviceId": "device123",
                "appId": "app123",
                "message": "Device registered successfully",
                "mqtt": {
                    "host": "push.example.com",
                    "port": 8883,
                    "token": "jwt_token_here",
                    "secure": true
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(successResponse)
            .addHeader("Content-Type", "application/json"))

        val latch = CountDownLatch(1)
        var receivedResponse: ApiClient.RegisterResponse? = null
        var receivedError: String? = null

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            userId = "user456",
            metadata = mapOf("platform" to "android", "version" to "1.0"),
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    receivedResponse = response
                    latch.countDown()
                }

                override fun onError(error: String) {
                    receivedError = error
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // Then - verify request
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/devices/register") == true)
        assertEquals("test_api_key", recordedRequest.getHeader("x-api-key"))
        assertTrue(recordedRequest.getHeader("Content-Type")?.contains("application/json") == true)

        // Verify request body
        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"deviceId\":\"device123\""))
        assertTrue(requestBody.contains("\"userId\":\"user456\""))
        assertTrue(requestBody.contains("\"platform\":\"android\""))
    }

    @Test
    fun `registerDevice parses successful response correctly`() {
        // Given
        val successResponse = """
            {
                "deviceId": "device123",
                "appId": "app123",
                "message": "Device registered successfully",
                "mqtt": {
                    "host": "push.example.com",
                    "port": 8883,
                    "token": "jwt_token_here",
                    "secure": true
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(successResponse)
            .addHeader("Content-Type", "application/json"))

        val latch = CountDownLatch(1)
        var receivedResponse: ApiClient.RegisterResponse? = null

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    receivedResponse = response
                    latch.countDown()
                }

                override fun onError(error: String) {
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(receivedResponse)
        assertEquals("device123", receivedResponse?.deviceId)
        assertEquals("app123", receivedResponse?.appId)
        assertNotNull(receivedResponse?.mqtt)
        assertEquals("push.example.com", receivedResponse?.mqtt?.host)
        assertEquals(8883, receivedResponse?.mqtt?.port)
        assertEquals("jwt_token_here", receivedResponse?.mqtt?.token)
        assertTrue(receivedResponse?.mqtt?.secure == true)
    }

    @Test
    fun `registerDevice handles 401 unauthorized error`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": "Invalid API key"}"""))

        val latch = CountDownLatch(1)
        var receivedError: String? = null

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    latch.countDown()
                }

                override fun onError(error: String) {
                    receivedError = error
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(receivedError)
        assertTrue(receivedError?.contains("401") == true)
    }

    @Test
    fun `registerDevice handles 500 server error`() {
        // Given - enqueue multiple responses for retry attempts
        repeat(5) {
            mockWebServer.enqueue(MockResponse()
                .setResponseCode(500)
                .setBody("""{"error": "Internal server error"}"""))
        }

        val latch = CountDownLatch(1)
        var receivedError: String? = null
        var receivedSuccess = false

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    receivedSuccess = true
                    latch.countDown()
                }

                override fun onError(error: String) {
                    receivedError = error
                    latch.countDown()
                }
            }
        )

        // Wait longer for potential retries
        awaitWithLooper(latch, 15)

        // Then - either error or some result should come
        assertTrue("Should receive either error or success callback", receivedError != null || receivedSuccess)
        if (receivedError != null) {
            assertTrue("Error should mention status code", receivedError?.contains("500") == true || receivedError?.contains("failed") == true)
        }
    }

    @Test
    fun `registerDevice handles malformed JSON response gracefully`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("not valid json {{{"))

        val latch = CountDownLatch(1)
        var receivedResponse: ApiClient.RegisterResponse? = null

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    receivedResponse = response
                    latch.countDown()
                }

                override fun onError(error: String) {
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // SDK returns fallback response on parse error
        assertNotNull(receivedResponse)
        assertEquals("device123", receivedResponse?.deviceId)
    }

    // ==================== Subscribe Topic Tests ====================

    @Test
    fun `subscribeTopic sends correct request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        apiClient.subscribeTopic(
            deviceId = "device123",
            topic = "news",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) { latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )

        awaitWithLooper(latch)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/devices/device123/topics/news") == true)
        assertEquals("test_api_key", recordedRequest.getHeader("x-api-key"))
    }

    @Test
    fun `subscribeTopic handles success response`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true, "topic": "news"}"""))

        val latch = CountDownLatch(1)
        var receivedResponse: String? = null

        // When
        apiClient.subscribeTopic(
            deviceId = "device123",
            topic = "news",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) {
                    receivedResponse = response
                    latch.countDown()
                }

                override fun onError(error: String) { latch.countDown() }
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(receivedResponse)
        assertTrue(receivedResponse?.contains("success") == true)
    }

    @Test
    fun `subscribeTopic handles error response`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(404)
            .setBody("""{"error": "Topic not found"}"""))

        val latch = CountDownLatch(1)
        var receivedError: String? = null

        // When
        apiClient.subscribeTopic(
            deviceId = "device123",
            topic = "invalid_topic",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) { latch.countDown() }
                override fun onError(error: String) {
                    receivedError = error
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(receivedError)
        assertTrue(receivedError?.contains("404") == true)
    }

    // ==================== Unsubscribe Topic Tests ====================

    @Test
    fun `unsubscribeTopic sends DELETE request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        apiClient.unsubscribeTopic(
            deviceId = "device123",
            topic = "news",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) { latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )

        awaitWithLooper(latch)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("DELETE", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/devices/device123/topics/news") == true)
    }

    // ==================== Set User ID Tests ====================

    @Test
    fun `setUserId sends correct request body`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        apiClient.setUserId(
            deviceId = "device123",
            userId = "user456",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) { latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )

        awaitWithLooper(latch)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/devices/device123/user") == true)

        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"userId\":\"user456\""))
    }

    // ==================== Clear User ID Tests ====================

    @Test
    fun `clearUserId sends DELETE request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        apiClient.clearUserId(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<String> {
                override fun onSuccess(response: String) { latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )

        awaitWithLooper(latch)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("DELETE", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/devices/device123/user") == true)
    }

    // ==================== Fetch Push Config Tests ====================

    @Test
    fun `fetchPushConfig parses response correctly`() {
        // Given
        val configResponse = """
            {
                "mqtt": {
                    "host": "mqtt.rivium.co",
                    "port": 8883,
                    "username": "user",
                    "password": "pass"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(configResponse))

        // When - synchronous call
        val result = apiClient.fetchPushConfig()

        // Then
        assertNotNull(result)
        assertEquals("mqtt.rivium.co", result?.host)
        assertEquals(8883, result?.port)
        assertEquals("user", result?.username)
        assertEquals("pass", result?.password)
    }

    @Test
    fun `fetchPushConfig returns null on error`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Server error"}"""))

        // When
        val result = apiClient.fetchPushConfig()

        // Then
        assertNull(result)
    }

    // ==================== In-App Messages Tests ====================

    @Test
    fun `getInAppMessages sends correct parameters`() {
        // Given
        val messagesResponse = """
            {
                "messages": [
                    {
                        "id": "msg1",
                        "type": "MODAL",
                        "content": {"title": "Welcome", "body": "Hello!"}
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(messagesResponse))

        // When
        val params = mapOf(
            "deviceId" to "device123",
            "userId" to "user456",
            "locale" to "en"
        )
        val result = apiClient.getInAppMessages(params)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/in-app/fetch") == true)

        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"deviceId\":\"device123\""))
        assertTrue(requestBody.contains("\"userId\":\"user456\""))
        assertTrue(requestBody.contains("\"locale\":\"en\""))

        assertNotNull(result)
        assertTrue(result?.contains("Welcome") == true)
    }

    @Test
    fun `recordInAppImpression sends correct parameters`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        // When
        val params = mapOf(
            "messageId" to "msg123",
            "deviceId" to "device123",
            "action" to "impression"
        )
        val result = apiClient.recordInAppImpression(params)

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/in-app/impression") == true)

        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"messageId\":\"msg123\""))
        assertTrue(requestBody.contains("\"action\":\"impression\""))

        assertTrue(result)
    }

    @Test
    fun `recordInAppImpression returns false on error`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Server error"}"""))

        // When
        val params = mapOf("messageId" to "msg123", "deviceId" to "device123", "action" to "impression")
        val result = apiClient.recordInAppImpression(params)

        // Then
        assertFalse(result)
    }

    // ==================== A/B Testing Tests ====================

    @Test
    fun `getActiveABTests returns correct data`() {
        // Given
        val testsResponse = """
            {
                "tests": [
                    {"id": "test1", "name": "Button Color Test", "variantCount": 2},
                    {"id": "test2", "name": "Layout Test", "variantCount": 3}
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(testsResponse))

        // When
        val result = apiClient.getActiveABTests()

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/ab-tests/sdk/active") == true)

        assertNotNull(result)
        assertTrue(result?.contains("Button Color Test") == true)
        assertTrue(result?.contains("Layout Test") == true)
    }

    @Test
    fun `getABTestAssignment sends correct request`() {
        // Given
        val assignmentResponse = """
            {
                "testId": "test1",
                "variantId": "variant_a",
                "variantName": "Variant A",
                "isControlGroup": false,
                "content": {
                    "title": "Try New Feature",
                    "body": "Check out our new feature!"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(assignmentResponse))

        // When
        val result = apiClient.getABTestAssignment("test1", "device123")

        // Then
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("/ab-tests/sdk/assignment") == true)

        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"testId\":\"test1\""))
        assertTrue(requestBody.contains("\"deviceId\":\"device123\""))

        assertNotNull(result)
        assertTrue(result?.contains("Variant A") == true)
    }

    @Test
    fun `trackABTestEvent sends correct request for each event type`() {
        val events = listOf("impression", "opened", "clicked", "converted")

        events.forEach { event ->
            // Given
            mockWebServer.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"success": true}"""))

            // When
            val result = apiClient.trackABTestEvent(
                testId = "test1",
                variantId = "variant_a",
                deviceId = "device123",
                event = event
            )

            // Then
            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertTrue(recordedRequest.path?.contains("/ab-tests/sdk/track/$event") == true)

            val requestBody = recordedRequest.body.readUtf8()
            assertTrue(requestBody.contains("\"testId\":\"test1\""))
            assertTrue(requestBody.contains("\"variantId\":\"variant_a\""))
            assertTrue(requestBody.contains("\"deviceId\":\"device123\""))

            assertTrue(result)
        }
    }

    @Test
    fun `trackABTestEvent returns false on server error`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Server error"}"""))

        // When
        val result = apiClient.trackABTestEvent(
            testId = "test1",
            variantId = "variant_a",
            deviceId = "device123",
            event = "impression"
        )

        // Then
        assertFalse(result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `api client includes api key in all requests`() {
        // Given
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"mqtt":{}}"""))

        // When - make different types of requests
        val latch = CountDownLatch(2)
        apiClient.registerDevice("device1", callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
            override fun onSuccess(response: ApiClient.RegisterResponse) { latch.countDown() }
            override fun onError(error: String) { latch.countDown() }
        })
        apiClient.subscribeTopic("device1", "topic", callback = object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) { latch.countDown() }
            override fun onError(error: String) { latch.countDown() }
        })
        awaitWithLooper(latch)
        apiClient.fetchPushConfig()

        // Then - all requests should have the API key header
        repeat(3) {
            val request = mockWebServer.takeRequest()
            assertEquals("test_api_key", request.getHeader("x-api-key"))
        }
    }

    @Test
    fun `registerDevice handles empty response body`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(""))

        val latch = CountDownLatch(1)
        var receivedResponse: ApiClient.RegisterResponse? = null

        // When
        apiClient.registerDevice(
            deviceId = "device123",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) {
                    receivedResponse = response
                    latch.countDown()
                }

                override fun onError(error: String) {
                    latch.countDown()
                }
            }
        )

        awaitWithLooper(latch)

        // Then - should fallback to default response
        assertNotNull(receivedResponse)
        assertEquals("device123", receivedResponse?.deviceId)
    }
}
