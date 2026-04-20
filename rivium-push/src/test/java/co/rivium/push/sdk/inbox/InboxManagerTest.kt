package co.rivium.push.sdk.inbox

import android.content.Context
import android.os.Looper
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
 * Unit tests for InboxManager.
 * Uses Robolectric for Android context and MockWebServer for API mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InboxManagerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var config: RiviumPushConfig
    private lateinit var inboxManager: InboxManager

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Point to mock server
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        RiviumPushConfig.SERVER_URL = baseUrl

        context = RuntimeEnvironment.getApplication()
        config = RiviumPushConfig(apiKey = "test_api_key")

        // Get fresh instance for each test
        inboxManager = InboxManager.getInstance(context, config, "device123", "user456")
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        RiviumPushConfig.resetDefaults()
        inboxManager.clearCache()
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

    // ==================== Get Messages Tests ====================

    @Test
    fun `getMessages returns parsed messages on success`() {
        // Given
        val responseJson = """
            {
                "messages": [
                    {
                        "id": "msg1",
                        "content": {"title": "Hello", "body": "World"},
                        "status": "unread",
                        "createdAt": "2024-01-01T00:00:00Z"
                    },
                    {
                        "id": "msg2",
                        "content": {"title": "Second", "body": "Message"},
                        "status": "read",
                        "createdAt": "2024-01-02T00:00:00Z"
                    }
                ],
                "total": 10,
                "unreadCount": 5
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)
        var response: InboxMessagesResponse? = null
        var error: String? = null

        // When
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = {
                response = it
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
        assertNotNull(response)
        assertEquals(2, response?.messages?.size)
        assertEquals("msg1", response?.messages?.get(0)?.id)
        assertEquals("Hello", response?.messages?.get(0)?.content?.title)
        assertEquals(10, response?.total)
        assertEquals(5, response?.unreadCount)
    }

    @Test
    fun `getMessages sends correct request headers`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"messages": [], "total": 0, "unreadCount": 0}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/inbox/messages") == true)
        assertEquals("test_api_key", request.getHeader("x-api-key"))
        assertTrue(request.getHeader("Content-Type")?.contains("application/json") == true)
    }

    @Test
    fun `getMessages applies filter parameters`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"messages": [], "total": 0, "unreadCount": 0}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.getMessages(
            filter = InboxFilter(
                status = InboxMessageStatus.UNREAD,
                category = "promo",
                limit = 20,
                offset = 10,
                locale = "en-US"
            ),
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"status\":\"unread\""))
        assertTrue(body.contains("\"category\":\"promo\""))
        assertTrue(body.contains("\"limit\":20"))
        assertTrue(body.contains("\"offset\":10"))
        assertTrue(body.contains("\"locale\":\"en-US\""))
    }

    @Test
    fun `getMessages returns error on server failure`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Internal server error"}"""))

        val latch = CountDownLatch(1)
        var error: String? = null

        // When
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch.countDown() },
            onError = {
                error = it
                latch.countDown()
            }
        )

        awaitWithLooper(latch)

        // Then
        assertNotNull(error)
        assertTrue(error?.contains("500") == true)
    }

    @Test
    fun `getMessages caches results`() {
        // Given
        val responseJson = """
            {
                "messages": [
                    {
                        "id": "cached_msg",
                        "content": {"title": "Cached", "body": "Message"},
                        "status": "unread",
                        "createdAt": "2024-01-01T00:00:00Z"
                    }
                ],
                "total": 1,
                "unreadCount": 1
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)

        // When
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then - check cached messages
        val cached = inboxManager.getCachedMessages()
        assertEquals(1, cached.size)
        assertEquals("cached_msg", cached[0].id)
    }

    // ==================== Mark As Read Tests ====================

    @Test
    fun `markAsRead sends correct request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.markAsRead(
            messageId = "msg123",
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.path?.contains("/inbox/messages/msg123") == true)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"status\":\"read\""))
    }

    @Test
    fun `markAsRead updates local cache`() {
        // First, populate cache
        val responseJson = """
            {
                "messages": [
                    {
                        "id": "msg1",
                        "content": {"title": "Test", "body": "Message"},
                        "status": "unread",
                        "createdAt": "2024-01-01T00:00:00Z"
                    }
                ],
                "total": 1,
                "unreadCount": 1
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch1 = CountDownLatch(1)
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch1.countDown() },
            onError = { latch1.countDown() }
        )
        latch1.await(5, TimeUnit.SECONDS)

        // Now mark as read
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch2 = CountDownLatch(1)
        inboxManager.markAsRead(
            messageId = "msg1",
            onSuccess = { latch2.countDown() },
            onError = { latch2.countDown() }
        )
        latch2.await(5, TimeUnit.SECONDS)

        // Then - cached message should be updated
        val cached = inboxManager.getCachedMessages()
        assertEquals(InboxMessageStatus.READ, cached.find { it.id == "msg1" }?.status)
    }

    // ==================== Delete Message Tests ====================

    @Test
    fun `deleteMessage sends DELETE request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.deleteMessage(
            messageId = "msg_to_delete",
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.path?.contains("/inbox/messages/msg_to_delete") == true)
    }

    @Test
    fun `deleteMessage removes from local cache`() {
        // First, populate cache
        val responseJson = """
            {
                "messages": [
                    {"id": "msg1", "content": {"title": "Keep", "body": "Me"}, "status": "read", "createdAt": "2024-01-01T00:00:00Z"},
                    {"id": "msg2", "content": {"title": "Delete", "body": "Me"}, "status": "read", "createdAt": "2024-01-02T00:00:00Z"}
                ],
                "total": 2,
                "unreadCount": 0
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch1 = CountDownLatch(1)
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch1.countDown() },
            onError = { latch1.countDown() }
        )
        latch1.await(5, TimeUnit.SECONDS)
        assertEquals(2, inboxManager.getCachedMessages().size)

        // Now delete
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch2 = CountDownLatch(1)
        inboxManager.deleteMessage(
            messageId = "msg2",
            onSuccess = { latch2.countDown() },
            onError = { latch2.countDown() }
        )
        latch2.await(5, TimeUnit.SECONDS)

        // Then
        val cached = inboxManager.getCachedMessages()
        assertEquals(1, cached.size)
        assertEquals("msg1", cached[0].id)
    }

    // ==================== Mark Multiple Tests ====================

    @Test
    fun `markMultiple sends correct request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.markMultiple(
            messageIds = listOf("msg1", "msg2", "msg3"),
            status = InboxMessageStatus.ARCHIVED,
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/inbox/messages/mark-multiple") == true)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"messageIds\""))
        assertTrue(body.contains("msg1"))
        assertTrue(body.contains("msg2"))
        assertTrue(body.contains("msg3"))
        assertTrue(body.contains("\"status\":\"archived\""))
    }

    // ==================== Mark All As Read Tests ====================

    @Test
    fun `markAllAsRead sends correct request`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch = CountDownLatch(1)

        // When
        inboxManager.markAllAsRead(
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/inbox/messages/mark-all-read") == true)
    }

    @Test
    fun `markAllAsRead updates unread count to zero`() {
        // First, populate cache with unread messages
        val responseJson = """
            {
                "messages": [
                    {"id": "msg1", "content": {"title": "A", "body": "A"}, "status": "unread", "createdAt": "2024-01-01T00:00:00Z"},
                    {"id": "msg2", "content": {"title": "B", "body": "B"}, "status": "unread", "createdAt": "2024-01-02T00:00:00Z"}
                ],
                "total": 2,
                "unreadCount": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch1 = CountDownLatch(1)
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch1.countDown() },
            onError = { latch1.countDown() }
        )
        latch1.await(5, TimeUnit.SECONDS)
        assertEquals(2, inboxManager.getUnreadCount())

        // Now mark all as read
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success": true}"""))

        val latch2 = CountDownLatch(1)
        inboxManager.markAllAsRead(
            onSuccess = { latch2.countDown() },
            onError = { latch2.countDown() }
        )
        latch2.await(5, TimeUnit.SECONDS)

        // Then
        assertEquals(0, inboxManager.getUnreadCount())
    }

    // ==================== Fetch Unread Count Tests ====================

    @Test
    fun `fetchUnreadCount returns correct count`() {
        // Given
        val responseJson = """
            {
                "messages": [],
                "total": 100,
                "unreadCount": 42
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseJson))

        val latch = CountDownLatch(1)
        var count: Int? = null

        // When
        inboxManager.fetchUnreadCount(
            onSuccess = {
                count = it
                latch.countDown()
            },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        assertEquals(42, count)
        assertEquals(42, inboxManager.getUnreadCount())
    }

    // ==================== Handle Incoming Message Tests ====================

    @Test
    fun `handleIncomingMessage adds message to cache`() {
        // Given
        val message = InboxMessage(
            id = "new_msg",
            content = InboxContent(title = "New", body = "Message"),
            status = InboxMessageStatus.UNREAD,
            createdAt = "2024-01-01T00:00:00Z"
        )

        val initialCount = inboxManager.getUnreadCount()

        // When
        inboxManager.handleIncomingMessage(message)

        // Then
        val cached = inboxManager.getCachedMessages()
        assertTrue(cached.any { it.id == "new_msg" })
        assertEquals(initialCount + 1, inboxManager.getUnreadCount())
    }

    @Test
    fun `handleIncomingMessage adds to beginning of list`() {
        // First add some existing messages
        val existingMessage = InboxMessage(
            id = "existing",
            content = InboxContent(title = "Existing", body = "Message"),
            status = InboxMessageStatus.READ,
            createdAt = "2024-01-01T00:00:00Z"
        )
        inboxManager.handleIncomingMessage(existingMessage)

        // Now add new message
        val newMessage = InboxMessage(
            id = "new",
            content = InboxContent(title = "New", body = "Message"),
            status = InboxMessageStatus.UNREAD,
            createdAt = "2024-01-02T00:00:00Z"
        )
        inboxManager.handleIncomingMessage(newMessage)

        // Then
        val cached = inboxManager.getCachedMessages()
        assertEquals("new", cached[0].id)
        assertEquals("existing", cached[1].id)
    }

    // ==================== Callback Tests ====================

    @Test
    fun `callback is invoked on message status change`() {
        // Given
        var callbackMessageId: String? = null
        var callbackStatus: InboxMessageStatus? = null

        inboxManager.setCallback(object : InboxCallback {
            override fun onMessageReceived(message: InboxMessage) {}
            override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) {
                callbackMessageId = messageId
                callbackStatus = status
            }
        })

        // Populate cache
        val responseJson = """
            {
                "messages": [{"id": "msg1", "content": {"title": "T", "body": "B"}, "status": "unread", "createdAt": "2024-01-01T00:00:00Z"}],
                "total": 1,
                "unreadCount": 1
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val latch1 = CountDownLatch(1)
        inboxManager.getMessages(InboxFilter(), { latch1.countDown() }, { latch1.countDown() })
        awaitWithLooper(latch1)

        // Mark as read
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success": true}"""))
        val latch2 = CountDownLatch(1)
        inboxManager.markAsRead("msg1", { latch2.countDown() }, { latch2.countDown() })
        awaitWithLooper(latch2)

        // Flush the main looper to ensure callback executes
        shadowOf(Looper.getMainLooper()).idle()

        // Then
        assertEquals("msg1", callbackMessageId)
        assertEquals(InboxMessageStatus.READ, callbackStatus)
    }

    @Test
    fun `callback is invoked on incoming message`() {
        // Given
        var receivedMessage: InboxMessage? = null

        inboxManager.setCallback(object : InboxCallback {
            override fun onMessageReceived(message: InboxMessage) {
                receivedMessage = message
            }
            override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) {}
        })

        val message = InboxMessage(
            id = "callback_test",
            content = InboxContent(title = "Callback", body = "Test"),
            status = InboxMessageStatus.UNREAD,
            createdAt = "2024-01-01T00:00:00Z"
        )

        // When
        inboxManager.handleIncomingMessage(message)

        // Allow callback to execute on main thread
        Thread.sleep(100)

        // Then
        assertEquals("callback_test", receivedMessage?.id)
    }

    // ==================== Clear Cache Tests ====================

    @Test
    fun `clearCache removes all cached data`() {
        // Given - add some data
        val message = InboxMessage(
            id = "to_clear",
            content = InboxContent(title = "Clear", body = "Me"),
            status = InboxMessageStatus.UNREAD,
            createdAt = "2024-01-01T00:00:00Z"
        )
        inboxManager.handleIncomingMessage(message)
        assertTrue(inboxManager.getCachedMessages().isNotEmpty())

        // When
        inboxManager.clearCache()

        // Then
        assertTrue(inboxManager.getCachedMessages().isEmpty())
        assertEquals(0, inboxManager.getUnreadCount())
    }

    // ==================== User ID Tests ====================

    @Test
    fun `setUserId updates userId for requests`() {
        // Given
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"messages": [], "total": 0, "unreadCount": 0}"""))

        inboxManager.setUserId("new_user_id")

        val latch = CountDownLatch(1)

        // When
        inboxManager.getMessages(
            filter = InboxFilter(),
            onSuccess = { latch.countDown() },
            onError = { latch.countDown() }
        )

        awaitWithLooper(latch)

        // Then
        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"userId\":\"new_user_id\""))
    }
}
