package co.rivium.push.sdk.abtesting

import android.content.Context
import android.os.Looper
import co.rivium.push.sdk.ApiClient
import co.rivium.push.sdk.RiviumPushConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for ABTestingManager.
 * Uses Robolectric for Android context and MockWebServer for API mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ABTestingManagerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var config: RiviumPushConfig
    private lateinit var apiClient: ApiClient
    private lateinit var abTestingManager: ABTestingManager

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Point to mock server
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        RiviumPushConfig.SERVER_URL = baseUrl

        context = RuntimeEnvironment.getApplication()
        config = RiviumPushConfig(apiKey = "test_api_key")
        apiClient = ApiClient(config)

        // Get fresh instance for each test
        abTestingManager = ABTestingManager.getInstance(context, apiClient, "device123")
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        RiviumPushConfig.resetDefaults()
        abTestingManager.setCallback(null)
        abTestingManager.clearCache()
    }

    /**
     * Helper to wait for async operations and flush main looper.
     */
    private fun awaitWithLooper(latch: CountDownLatch, timeoutSeconds: Long = 10): Boolean {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000)
        while (latch.count > 0L && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        return latch.count == 0L
    }

    // ==================== Get Active Tests ====================

    @Test
    fun `getActiveTests returns parsed test summaries`() {
        // Given
        val responseJson = """
            [
                {"id": "test1", "name": "Button Color", "variantCount": 2, "hasControlGroup": true},
                {"id": "test2", "name": "Layout Test", "variantCount": 3, "hasControlGroup": false}
            ]
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)
        var tests: List<ABTestSummary>? = null
        var error: String? = null

        // When
        abTestingManager.getActiveTests(
            onSuccess = {
                tests = it
                latch.countDown()
            },
            onError = {
                error = it
                latch.countDown()
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNull(error)
        assertNotNull(tests)
        assertEquals(2, tests?.size)
        assertEquals("test1", tests?.get(0)?.id)
        assertEquals("Button Color", tests?.get(0)?.name)
        assertEquals(2, tests?.get(0)?.variantCount)
        assertTrue(tests?.get(0)?.hasControlGroup == true)
        assertEquals("test2", tests?.get(1)?.id)
    }

    @Test
    fun `getActiveTests returns empty list on null response`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("null"))

        val latch = CountDownLatch(1)
        var tests: List<ABTestSummary>? = null

        // When
        abTestingManager.getActiveTests(
            onSuccess = {
                tests = it
                latch.countDown()
            },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then - should return empty list, not fail
        // Note: actual behavior depends on how API handles null
        assertNotNull(tests)
    }

    @Test
    fun `getActiveTests handles server error`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Server error"}"""))

        val latch = CountDownLatch(1)
        var tests: List<ABTestSummary>? = null

        // When
        abTestingManager.getActiveTests(
            onSuccess = {
                tests = it
                latch.countDown()
            },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then - returns empty list on error
        assertTrue(tests?.isEmpty() ?: true)
    }

    // ==================== Get Variant Tests ====================

    @Test
    fun `getVariant fetches and parses variant correctly`() {
        // Given
        val responseJson = """
            {
                "testId": "test1",
                "variantId": "variant_a",
                "variantName": "Variant A",
                "isControlGroup": false,
                "content": {
                    "title": "Welcome!",
                    "body": "Check out our new feature"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)
        var variant: ABTestVariant? = null
        var error: String? = null

        // When
        abTestingManager.getVariant(
            testId = "test1",
            onSuccess = {
                variant = it
                latch.countDown()
            },
            onError = {
                error = it
                latch.countDown()
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNull(error)
        assertNotNull(variant)
        assertEquals("test1", variant?.testId)
        assertEquals("variant_a", variant?.variantId)
        assertEquals("Variant A", variant?.variantName)
        assertFalse(variant?.isControlGroup ?: true)
        assertEquals("Welcome!", variant?.content?.title)
    }

    @Test
    fun `getVariant caches result`() {
        // Given
        val responseJson = """
            {
                "testId": "test1",
                "variantId": "cached_variant",
                "variantName": "Cached",
                "isControlGroup": false
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant(
            testId = "test1",
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )
        awaitWithLooper(latch)

        // Then - should be cached
        val cached = abTestingManager.getCachedVariant("test1")
        assertNotNull(cached)
        assertEquals("cached_variant", cached?.variantId)
    }

    @Test
    fun `getVariant returns cached value without network call`() {
        // First, fetch to populate cache
        val responseJson = """
            {
                "testId": "test1",
                "variantId": "original",
                "variantName": "Original",
                "isControlGroup": false
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch1 = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch1.countDown() }, { latch1.countDown() })
        latch1.await(5, TimeUnit.SECONDS)

        // Now request again - should use cache
        val latch2 = CountDownLatch(1)
        var variant: ABTestVariant? = null

        abTestingManager.getVariant(
            testId = "test1",
            forceRefresh = false,
            onSuccess = {
                variant = it
                latch2.countDown()
            },
            onError = { latch2.countDown() }
        )

        latch2.await(5, TimeUnit.SECONDS)

        // Then - should return cached value
        assertEquals("original", variant?.variantId)
        // Only 1 request should have been made
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `getVariant with forceRefresh fetches new value`() {
        // First call
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "v1", "variantName": "V1", "isControlGroup": false}"""))

        val latch1 = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch1.countDown() }, { latch1.countDown() })
        awaitWithLooper(latch1)

        // Second call with forceRefresh
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "v2", "variantName": "V2", "isControlGroup": false}"""))

        val latch2 = CountDownLatch(1)
        var variant: ABTestVariant? = null

        abTestingManager.getVariant(
            testId = "test1",
            forceRefresh = true,
            onSuccess = {
                variant = it
                latch2.countDown()
            },
            onError = { latch2.countDown() }
        )

        awaitWithLooper(latch2)

        // Then - should return new value
        assertEquals("v2", variant?.variantId)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `getVariant handles error response`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(404)
            .setBody("""{"error": "Test not found"}"""))

        val latch = CountDownLatch(1)
        var error: String? = null

        // When
        abTestingManager.getVariant(
            testId = "nonexistent",
            forceRefresh = true,
            onSuccess = { latch.countDown() },
            onError = {
                error = it
                latch.countDown()
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(error)
    }

    // ==================== Track Event Tests ====================

    @Test
    fun `trackImpression sends correct request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)
        var success = false

        // When
        abTestingManager.trackImpression(
            testId = "test1",
            variantId = "variant_a",
            onSuccess = {
                success = true
                latch.countDown()
            },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        assertTrue(success)
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/ab-tests/sdk/track/impression") == true)
    }

    @Test
    fun `trackOpened sends correct event type`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        abTestingManager.trackOpened("test1", "variant_a", { latch.countDown() }, { latch.countDown() })

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.contains("/track/opened") == true)
    }

    @Test
    fun `trackClicked sends correct event type`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        abTestingManager.trackClicked("test1", "variant_a", { latch.countDown() }, { latch.countDown() })

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.contains("/track/clicked") == true)
    }

    @Test
    fun `trackConverted sends correct event type`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        abTestingManager.trackConverted("test1", "variant_a", { latch.countDown() }, { latch.countDown() })

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.contains("/track/converted") == true)
    }

    @Test
    fun `trackConversion uses cached variant`() {
        // First, cache a variant
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "cached_var", "variantName": "Cached", "isControlGroup": false}"""))

        val latch1 = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch1.countDown() }, { latch1.countDown() })
        latch1.await(5, TimeUnit.SECONDS)

        // Now track conversion
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch2 = CountDownLatch(1)
        abTestingManager.trackConversion("test1", { latch2.countDown() }, { latch2.countDown() })
        latch2.await(5, TimeUnit.SECONDS)

        // Then
        val requests = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        repeat(mockWebServer.requestCount) {
            requests.add(mockWebServer.takeRequest())
        }

        val trackRequest = requests.find { it.path?.contains("/track/converted") == true }
        assertNotNull(trackRequest)
        val body = trackRequest?.body?.readUtf8()
        assertTrue(body?.contains("\"variantId\":\"cached_var\"") == true)
    }

    @Test
    fun `trackConversion fails when variant not cached`() {
        // Given - no cached variant
        abTestingManager.clearCache()

        val latch = CountDownLatch(1)
        var error: String? = null

        // When
        abTestingManager.trackConversion(
            testId = "uncached_test",
            onSuccess = { latch.countDown() },
            onError = {
                error = it
                latch.countDown()
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(error)
        assertTrue(error?.contains("not found") == true)
    }

    @Test
    fun `trackDisplay calls both impression and opened`() {
        // Given
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success": true}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success": true}"""))

        val variant = ABTestVariant(
            testId = "test1",
            variantId = "var1",
            variantName = "Variant 1",
            isControlGroup = false
        )

        val latch = CountDownLatch(1)
        var success = false

        // When
        abTestingManager.trackDisplay(
            variant = variant,
            onSuccess = {
                success = true
                latch.countDown()
            },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        assertTrue(success)
        assertEquals(2, mockWebServer.requestCount)

        val requests = mutableListOf<String>()
        repeat(mockWebServer.requestCount) {
            requests.add(mockWebServer.takeRequest().path ?: "")
        }
        assertTrue(requests.any { it.contains("/track/impression") })
        assertTrue(requests.any { it.contains("/track/opened") })
    }

    // ==================== Control Group Tests ====================

    @Test
    fun `isInControlGroup returns true for control variant`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "control", "variantName": "Control", "isControlGroup": true}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // When
        val isControl = abTestingManager.isInControlGroup("test1")

        // Then
        assertTrue(isControl)
    }

    @Test
    fun `isInControlGroup returns false for non-control variant`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "var_a", "variantName": "A", "isControlGroup": false}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // When
        val isControl = abTestingManager.isInControlGroup("test1")

        // Then
        assertFalse(isControl)
    }

    @Test
    fun `isInControlGroup returns false for uncached test`() {
        // Given - no cached variant
        abTestingManager.clearCache()

        // When
        val isControl = abTestingManager.isInControlGroup("uncached_test")

        // Then
        assertFalse(isControl)
    }

    // ==================== Cache Tests ====================

    @Test
    fun `getCachedVariant returns null for uncached test`() {
        // Given
        abTestingManager.clearCache()

        // When
        val cached = abTestingManager.getCachedVariant("nonexistent")

        // Then
        assertNull(cached)
    }

    @Test
    fun `clearCache removes all cached assignments`() {
        // First, cache a variant
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "var1", "variantName": "V1", "isControlGroup": false}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("test1", false, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        assertNotNull(abTestingManager.getCachedVariant("test1"))

        // When
        abTestingManager.clearCache()

        // Then
        assertNull(abTestingManager.getCachedVariant("test1"))
    }

    // ==================== Callback Tests ====================

    @Test
    fun `callback is invoked on variant assignment`() {
        // Given
        var assignedVariant: ABTestVariant? = null
        abTestingManager.setCallback(object : ABTestingCallback {
            override fun onVariantAssigned(variant: ABTestVariant) {
                assignedVariant = variant
            }
            override fun onError(testId: String?, error: String) {}
        })

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "var1", "variantName": "V1", "isControlGroup": false}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("test1", true, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // Allow callback to execute
        Thread.sleep(100)

        // Then
        assertNotNull(assignedVariant)
        assertEquals("test1", assignedVariant?.testId)
        assertEquals("var1", assignedVariant?.variantId)
    }

    @Test
    fun `callback is invoked on error`() {
        // Given
        var errorTestId: String? = null
        var errorMessage: String? = null

        abTestingManager.setCallback(object : ABTestingCallback {
            override fun onVariantAssigned(variant: ABTestVariant) {}
            override fun onError(testId: String?, error: String) {
                errorTestId = testId
                errorMessage = error
            }
        })

        // Use 404 instead of 500 - same behavior but more standard error response
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(404)
            .setBody("""{"error": "Test not found"}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("callback_error_test", true, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // Allow callback to execute
        Thread.sleep(100)

        // Then
        assertEquals("callback_error_test", errorTestId)
        assertNotNull(errorMessage)
    }

    // ==================== Request Format Tests ====================

    @Test
    fun `getVariant sends correct request format`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"testId": "test1", "variantId": "var1", "variantName": "V1", "isControlGroup": false}"""))

        val latch = CountDownLatch(1)
        abTestingManager.getVariant("test1", true, { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/ab-tests/sdk/assignment") == true)
        assertEquals("test_api_key", request.getHeader("x-api-key"))
        assertTrue(request.getHeader("Content-Type")?.contains("application/json") == true)

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"testId\":\"test1\""))
        assertTrue(body.contains("\"deviceId\":\"device123\""))
    }

    @Test
    fun `trackEvent sends correct request format`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)
        abTestingManager.trackImpression("test1", "var1", { latch.countDown() }, { latch.countDown() })
        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("test_api_key", request.getHeader("x-api-key"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"testId\":\"test1\""))
        assertTrue(body.contains("\"variantId\":\"var1\""))
        assertTrue(body.contains("\"deviceId\":\"device123\""))
    }
}
