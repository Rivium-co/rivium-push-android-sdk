package co.rivium.push.sdk

import android.os.Looper
import co.rivium.push.sdk.inbox.InboxFilter
import co.rivium.push.sdk.inbox.InboxManager
import co.rivium.push.sdk.internal.UserTokenSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP-layer tests for the signed user token (x-user-token header, retry,
 * auth errors). Uses MockWebServer; Robolectric for Handler/Looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UserTokenHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient
    private val authErrors = java.util.Collections.synchronizedList(mutableListOf<RiviumPushAuthError>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RiviumPushConfig.SERVER_URL = server.url("/").toString().removeSuffix("/")
        UserTokenSession.reset()
        UserTokenSession.listener = RiviumPushAuthErrorListener { authErrors.add(it) }
        apiClient = ApiClient(RiviumPushConfig(apiKey = "test_api_key"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        RiviumPushConfig.resetDefaults()
        UserTokenSession.reset()
    }

    private fun jwt(sub: String = "user-1", expInSeconds: Long = 3600, tag: String = "a"): String {
        val exp = System.currentTimeMillis() / 1000 + expInSeconds
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"$sub\",\"exp\":$exp}".toByteArray())
        return "eyJhbGciOiJFUzI1NiJ9.$payload.sig$tag"
    }

    private fun unauthorized(code: String) = MockResponse().setResponseCode(401)
        .setBody("{\"statusCode\":401,\"error\":\"Unauthorized\",\"code\":\"$code\",\"message\":\"$code message\"}")

    private fun ok(body: String = "[]") = MockResponse().setResponseCode(200).setBody(body)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(latch: CountDownLatch, timeoutSeconds: Long = 5): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (latch.count > 0L && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        return latch.count == 0L
    }

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    // ==================== No provider: unchanged ====================

    @Test
    fun `without provider requests carry no user token and the same headers as before`() {
        server.enqueue(ok())
        server.enqueue(ok("{}"))

        apiClient.getActiveABTests()
        val get = take()
        assertNull(get.getHeader("x-user-token"))
        assertEquals(
            setOf("x-api-key", "x-rivium-sdk", "host", "connection", "accept-encoding", "user-agent"),
            get.headers.names().map { it.lowercase() }.toSet()
        )

        assertTrue(apiClient.trackABTestEvent("t1", "v1", "d1", "clicked"))
        val post = take()
        assertNull(post.getHeader("x-user-token"))
        assertEquals("test_api_key", post.getHeader("x-api-key"))
        assertEquals("{\"testId\":\"t1\",\"variantId\":\"v1\",\"deviceId\":\"d1\"}", post.body.readUtf8())
        assertEquals(2, server.requestCount)
        assertTrue(authErrors.isEmpty())
    }

    @Test
    fun `without provider a 401 is returned to the caller without retry`() {
        server.enqueue(unauthorized("token_expired"))
        assertNull(apiClient.getActiveABTests())
        assertEquals(1, server.requestCount)
    }

    // ==================== Header ====================

    @Test
    fun `provider token is sent on sync and async requests`() {
        val calls = AtomicInteger()
        val token = jwt()
        RiviumPush.setTokenProvider { calls.incrementAndGet(); token }
        server.enqueue(ok())
        server.enqueue(ok("{\"deviceId\":\"d1\"}"))

        apiClient.getActiveABTests()
        assertEquals(token, take().getHeader("x-user-token"))

        val latch = CountDownLatch(1)
        apiClient.registerDevice(
            deviceId = "d1",
            userId = "user-1",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) { latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )
        assertTrue(await(latch))
        val register = take()
        assertEquals(token, register.getHeader("x-user-token"))
        assertEquals("test_api_key", register.getHeader("x-api-key"))
        assertTrue(register.body.readUtf8().contains("\"userId\":\"user-1\""))
        assertEquals(1, calls.get())
    }

    @Test
    fun `blocking provider and setUserToken are sent too`() {
        val token = jwt(tag = "blocking")
        RiviumPush.setBlockingTokenProvider { token }
        server.enqueue(ok())
        apiClient.getActiveABTests()
        assertEquals(token, take().getHeader("x-user-token"))

        RiviumPush.setBlockingTokenProvider(null)
        val manual = jwt(tag = "manual")
        RiviumPush.setUserToken(manual)
        server.enqueue(ok())
        apiClient.getActiveABTests()
        assertEquals(manual, take().getHeader("x-user-token"))

        RiviumPush.setUserToken(null)
        server.enqueue(ok())
        apiClient.getActiveABTests()
        assertNull(take().getHeader("x-user-token"))
    }

    @Test
    fun `provider returning null sends no header and reports nothing`() {
        RiviumPush.setTokenProvider { null }
        server.enqueue(ok())
        apiClient.getActiveABTests()
        idle()
        assertNull(take().getHeader("x-user-token"))
        assertTrue(authErrors.isEmpty())
    }

    @Test
    fun `inbox requests carry the token`() {
        val token = jwt()
        RiviumPush.setTokenProvider { token }
        server.enqueue(ok("{\"messages\":[],\"total\":0,\"unreadCount\":0}"))

        val inbox = InboxManager.getInstance(
            RuntimeEnvironment.getApplication(), RiviumPushConfig(apiKey = "test_api_key"), "device123", "user-1"
        )
        val latch = CountDownLatch(1)
        inbox.getMessages(InboxFilter(), { latch.countDown() }, { latch.countDown() })
        assertTrue(await(latch))
        assertEquals(token, take().getHeader("x-user-token"))
    }

    // ==================== Retry ====================

    @Test
    fun `token_expired fetches a fresh token and retries once`() {
        val calls = AtomicInteger()
        val first = jwt(tag = "1")
        val second = jwt(tag = "2")
        RiviumPush.setTokenProvider { if (calls.incrementAndGet() == 1) first else second }
        server.enqueue(unauthorized("token_expired"))
        server.enqueue(ok("[{\"id\":\"t\"}]"))

        assertEquals("[{\"id\":\"t\"}]", apiClient.getActiveABTests())
        idle()

        assertEquals(2, server.requestCount)
        assertEquals(first, take().getHeader("x-user-token"))
        assertEquals(second, take().getHeader("x-user-token"))
        assertEquals(2, calls.get())
        assertTrue(authErrors.isEmpty())
    }

    @Test
    fun `token_expired twice does not loop`() {
        val calls = AtomicInteger()
        RiviumPush.setTokenProvider { jwt(tag = calls.incrementAndGet().toString()) }
        server.enqueue(unauthorized("token_expired"))
        server.enqueue(unauthorized("token_expired"))
        server.enqueue(ok())

        assertNull(apiClient.getActiveABTests())
        idle()

        assertEquals(2, server.requestCount)
        assertEquals(1, authErrors.size)
        assertEquals(RiviumPushAuthError.TOKEN_EXPIRED, authErrors[0].code)
    }

    @Test
    fun `token_expired on a POST retries with the same body`() {
        val calls = AtomicInteger()
        RiviumPush.setTokenProvider { jwt(tag = calls.incrementAndGet().toString()) }
        server.enqueue(unauthorized("token_expired"))
        server.enqueue(ok("{}"))

        val latch = CountDownLatch(1)
        var result = ""
        apiClient.setUserId("d1", "user-1", object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) { result = "ok"; latch.countDown() }
            override fun onError(error: String) { result = error; latch.countDown() }
        })
        assertTrue(await(latch))

        assertEquals("ok", result)
        val firstBody = take().body.readUtf8()
        assertEquals(firstBody, take().body.readUtf8())
        assertEquals("{\"userId\":\"user-1\"}", firstBody)
    }

    @Test
    fun `token_invalid is not retried and is reported`() {
        val calls = AtomicInteger()
        RiviumPush.setTokenProvider { calls.incrementAndGet(); jwt() }
        server.enqueue(unauthorized("token_invalid"))

        val latch = CountDownLatch(1)
        var error: String? = null
        apiClient.setUserId("d1", "user-1", object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) { latch.countDown() }
            override fun onError(e: String) { error = e; latch.countDown() }
        })
        assertTrue(await(latch))
        idle()

        assertEquals("Set user ID failed: 401", error) // the request's own error is unchanged
        assertEquals(1, server.requestCount)
        assertEquals(1, calls.get())
        assertEquals(1, authErrors.size)
        assertEquals(RiviumPushAuthError.TOKEN_INVALID, authErrors[0].code)
    }

    @Test
    fun `token_required is reported`() {
        server.enqueue(unauthorized("token_required"))
        assertNull(apiClient.getActiveABTests())
        idle()
        assertEquals(1, server.requestCount)
        assertEquals(RiviumPushAuthError.TOKEN_REQUIRED, authErrors.single().code)
    }

    @Test
    fun `403 userId mismatch is reported as token_mismatch without retry`() {
        RiviumPush.setTokenProvider { jwt(sub = "someone-else") }
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("{\"statusCode\":403,\"error\":\"Forbidden\",\"message\":\"userId does not match the user token\"}")
        )
        assertNull(apiClient.getActiveABTests())
        idle()
        assertEquals(1, server.requestCount)
        assertEquals(RiviumPushAuthError.TOKEN_MISMATCH, authErrors.single().code)
    }

    @Test
    fun `other 401 and 403 responses are not auth errors`() {
        RiviumPush.setTokenProvider { jwt() }
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Invalid API key\"}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("not json"))
        assertNull(apiClient.getActiveABTests())
        assertNull(apiClient.getActiveABTests())
        idle()
        assertEquals(2, server.requestCount)
        assertTrue(authErrors.isEmpty())
    }

    // ==================== Provider failure ====================

    @Test
    fun `provider failure still sends the request without header and reports it`() {
        RiviumPush.setTokenProvider { throw IllegalStateException("backend down") }
        server.enqueue(ok("{\"deviceId\":\"d1\"}"))

        val latch = CountDownLatch(1)
        var registered = false
        apiClient.registerDevice(
            deviceId = "d1",
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
                override fun onSuccess(response: ApiClient.RegisterResponse) { registered = true; latch.countDown() }
                override fun onError(error: String) { latch.countDown() }
            }
        )
        assertTrue(await(latch))
        idle()

        assertTrue(registered)
        assertNull(take().getHeader("x-user-token"))
        assertEquals(1, authErrors.size)
        assertEquals(RiviumPushAuthError.TOKEN_PROVIDER_FAILED, authErrors[0].code)
        assertEquals("backend down", authErrors[0].message)
    }
}
