package co.rivium.push.sdk.inbox

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Inbox message models and serialization.
 */
class InboxMessageTest {

    private val gson = Gson()

    // ==================== InboxMessage Tests ====================

    @Test
    fun `InboxMessage deserializes from JSON correctly`() {
        // Given
        val json = """
            {
                "id": "msg123",
                "userId": "user456",
                "deviceId": "device789",
                "content": {
                    "title": "Welcome!",
                    "body": "Thanks for joining us.",
                    "imageUrl": "https://example.com/image.png",
                    "iconUrl": "https://example.com/icon.png",
                    "deepLink": "app://home",
                    "data": {"key1": "value1", "key2": 123}
                },
                "status": "unread",
                "category": "welcome",
                "expiresAt": "2024-12-31T23:59:59Z",
                "readAt": null,
                "createdAt": "2024-01-01T00:00:00Z",
                "updatedAt": "2024-01-02T00:00:00Z"
            }
        """.trimIndent()

        // When
        val message = gson.fromJson(json, InboxMessage::class.java)

        // Then
        assertEquals("msg123", message.id)
        assertEquals("user456", message.userId)
        assertEquals("device789", message.deviceId)
        assertEquals("Welcome!", message.content.title)
        assertEquals("Thanks for joining us.", message.content.body)
        assertEquals("https://example.com/image.png", message.content.imageUrl)
        assertEquals("https://example.com/icon.png", message.content.iconUrl)
        assertEquals("app://home", message.content.deepLink)
        assertNotNull(message.content.data)
        assertEquals("value1", message.content.data?.get("key1"))
        assertEquals(InboxMessageStatus.UNREAD, message.status)
        assertEquals("welcome", message.category)
        assertEquals("2024-12-31T23:59:59Z", message.expiresAt)
        assertNull(message.readAt)
        assertEquals("2024-01-01T00:00:00Z", message.createdAt)
        assertEquals("2024-01-02T00:00:00Z", message.updatedAt)
    }

    @Test
    fun `InboxMessage deserializes with minimal fields`() {
        // Given
        val json = """
            {
                "id": "msg123",
                "content": {
                    "title": "Test",
                    "body": "Test body"
                },
                "createdAt": "2024-01-01T00:00:00Z"
            }
        """.trimIndent()

        // When
        val message = gson.fromJson(json, InboxMessage::class.java)

        // Then
        assertEquals("msg123", message.id)
        assertEquals("Test", message.content.title)
        assertEquals("Test body", message.content.body)
        assertNull(message.userId)
        assertNull(message.deviceId)
        assertNull(message.content.imageUrl)
        assertNull(message.content.deepLink)
        assertNull(message.category)
    }

    @Test
    fun `InboxMessage serializes to JSON correctly`() {
        // Given
        val content = InboxContent(
            title = "Hello",
            body = "World",
            imageUrl = "https://img.com/1.png",
            deepLink = "app://page"
        )
        val message = InboxMessage(
            id = "msg1",
            userId = "user1",
            content = content,
            status = InboxMessageStatus.READ,
            createdAt = "2024-01-01T00:00:00Z"
        )

        // When
        val json = gson.toJson(message)

        // Then
        assertTrue(json.contains("\"id\":\"msg1\""))
        assertTrue(json.contains("\"userId\":\"user1\""))
        assertTrue(json.contains("\"title\":\"Hello\""))
        assertTrue(json.contains("\"body\":\"World\""))
        assertTrue(json.contains("\"status\":\"read\""))
    }

    // ==================== InboxMessageStatus Tests ====================

    @Test
    fun `InboxMessageStatus deserializes all values correctly`() {
        val testCases = mapOf(
            "\"unread\"" to InboxMessageStatus.UNREAD,
            "\"read\"" to InboxMessageStatus.READ,
            "\"archived\"" to InboxMessageStatus.ARCHIVED,
            "\"deleted\"" to InboxMessageStatus.DELETED
        )

        testCases.forEach { (json, expected) ->
            val result = gson.fromJson(json, InboxMessageStatus::class.java)
            assertEquals("Failed for $json", expected, result)
        }
    }

    @Test
    fun `InboxMessageStatus serializes with lowercase`() {
        // Verify SerializedName annotation works
        val statuses = mapOf(
            InboxMessageStatus.UNREAD to "\"unread\"",
            InboxMessageStatus.READ to "\"read\"",
            InboxMessageStatus.ARCHIVED to "\"archived\"",
            InboxMessageStatus.DELETED to "\"deleted\""
        )

        statuses.forEach { (status, expectedJson) ->
            val result = gson.toJson(status)
            assertEquals("Failed for $status", expectedJson, result)
        }
    }

    // ==================== InboxContent Tests ====================

    @Test
    fun `InboxContent deserializes with data map`() {
        // Given
        val json = """
            {
                "title": "Promo",
                "body": "50% off!",
                "data": {
                    "promoCode": "SAVE50",
                    "validUntil": "2024-12-31",
                    "discount": 50
                }
            }
        """.trimIndent()

        // When
        val content = gson.fromJson(json, InboxContent::class.java)

        // Then
        assertEquals("Promo", content.title)
        assertEquals("50% off!", content.body)
        assertNotNull(content.data)
        assertEquals("SAVE50", content.data?.get("promoCode"))
        assertEquals("2024-12-31", content.data?.get("validUntil"))
        // Note: Gson deserializes numbers as Double by default
        assertEquals(50.0, content.data?.get("discount"))
    }

    @Test
    fun `InboxContent handles null optional fields`() {
        // Given
        val json = """
            {
                "title": "Title",
                "body": "Body"
            }
        """.trimIndent()

        // When
        val content = gson.fromJson(json, InboxContent::class.java)

        // Then
        assertEquals("Title", content.title)
        assertEquals("Body", content.body)
        assertNull(content.imageUrl)
        assertNull(content.iconUrl)
        assertNull(content.deepLink)
        assertNull(content.data)
    }

    // ==================== InboxFilter Tests ====================

    @Test
    fun `InboxFilter has correct defaults`() {
        // When
        val filter = InboxFilter()

        // Then
        assertNull(filter.userId)
        assertNull(filter.deviceId)
        assertNull(filter.status)
        assertNull(filter.category)
        assertEquals(50, filter.limit)
        assertEquals(0, filter.offset)
        assertNull(filter.locale)
    }

    @Test
    fun `InboxFilter serializes correctly`() {
        // Given
        val filter = InboxFilter(
            userId = "user123",
            status = InboxMessageStatus.UNREAD,
            category = "promo",
            limit = 20,
            offset = 10,
            locale = "en-US"
        )

        // When
        val json = gson.toJson(filter)

        // Then
        assertTrue(json.contains("\"userId\":\"user123\""))
        assertTrue(json.contains("\"status\":\"unread\""))
        assertTrue(json.contains("\"category\":\"promo\""))
        assertTrue(json.contains("\"limit\":20"))
        assertTrue(json.contains("\"offset\":10"))
        assertTrue(json.contains("\"locale\":\"en-US\""))
    }

    // ==================== InboxMessagesResponse Tests ====================

    @Test
    fun `InboxMessagesResponse deserializes correctly`() {
        // Given
        val json = """
            {
                "messages": [
                    {
                        "id": "msg1",
                        "content": {"title": "First", "body": "First message"},
                        "status": "unread",
                        "createdAt": "2024-01-01T00:00:00Z"
                    },
                    {
                        "id": "msg2",
                        "content": {"title": "Second", "body": "Second message"},
                        "status": "read",
                        "createdAt": "2024-01-02T00:00:00Z"
                    }
                ],
                "total": 100,
                "unreadCount": 45
            }
        """.trimIndent()

        // When
        val response = gson.fromJson(json, InboxMessagesResponse::class.java)

        // Then
        assertEquals(2, response.messages.size)
        assertEquals("msg1", response.messages[0].id)
        assertEquals("First", response.messages[0].content.title)
        assertEquals(InboxMessageStatus.UNREAD, response.messages[0].status)
        assertEquals("msg2", response.messages[1].id)
        assertEquals(InboxMessageStatus.READ, response.messages[1].status)
        assertEquals(100, response.total)
        assertEquals(45, response.unreadCount)
    }

    @Test
    fun `InboxMessagesResponse handles empty messages list`() {
        // Given
        val json = """
            {
                "messages": [],
                "total": 0,
                "unreadCount": 0
            }
        """.trimIndent()

        // When
        val response = gson.fromJson(json, InboxMessagesResponse::class.java)

        // Then
        assertTrue(response.messages.isEmpty())
        assertEquals(0, response.total)
        assertEquals(0, response.unreadCount)
    }

    // ==================== toMap Extension Tests ====================

    @Test
    fun `InboxMessage toMap converts correctly`() {
        // Given
        val content = InboxContent(
            title = "Title",
            body = "Body",
            imageUrl = "https://img.com/1.png",
            deepLink = "app://page",
            data = mapOf("key" to "value")
        )
        val message = InboxMessage(
            id = "msg1",
            userId = "user1",
            deviceId = "device1",
            content = content,
            status = InboxMessageStatus.UNREAD,
            category = "news",
            createdAt = "2024-01-01T00:00:00Z"
        )

        // When
        val map = message.toMap()

        // Then
        assertEquals("msg1", map["id"])
        assertEquals("user1", map["userId"])
        assertEquals("device1", map["deviceId"])
        assertEquals("unread", map["status"])
        assertEquals("news", map["category"])
        assertEquals("2024-01-01T00:00:00Z", map["createdAt"])

        @Suppress("UNCHECKED_CAST")
        val contentMap = map["content"] as Map<String, Any?>
        assertEquals("Title", contentMap["title"])
        assertEquals("Body", contentMap["body"])
        assertEquals("https://img.com/1.png", contentMap["imageUrl"])
        assertEquals("app://page", contentMap["deepLink"])

        @Suppress("UNCHECKED_CAST")
        val dataMap = contentMap["data"] as Map<String, Any?>
        assertEquals("value", dataMap["key"])
    }

    @Test
    fun `InboxMessage toMap handles null values`() {
        // Given
        val content = InboxContent(title = "Title", body = "Body")
        val message = InboxMessage(
            id = "msg1",
            content = content,
            createdAt = "2024-01-01T00:00:00Z"
        )

        // When
        val map = message.toMap()

        // Then
        assertEquals("msg1", map["id"])
        assertNull(map["userId"])
        assertNull(map["deviceId"])
        assertNull(map["category"])
        assertNull(map["readAt"])

        @Suppress("UNCHECKED_CAST")
        val contentMap = map["content"] as Map<String, Any?>
        assertNull(contentMap["imageUrl"])
        assertNull(contentMap["deepLink"])
        assertNull(contentMap["data"])
    }

    // ==================== Edge Cases ====================

    @Test
    fun `InboxMessage with special characters in content`() {
        // Given
        val json = """
            {
                "id": "msg1",
                "content": {
                    "title": "Hello \"World\"!",
                    "body": "Line1\nLine2\tTabbed"
                },
                "createdAt": "2024-01-01T00:00:00Z"
            }
        """.trimIndent()

        // When
        val message = gson.fromJson(json, InboxMessage::class.java)

        // Then
        assertEquals("Hello \"World\"!", message.content.title)
        assertEquals("Line1\nLine2\tTabbed", message.content.body)
    }

    @Test
    fun `InboxMessage with unicode content`() {
        // Given
        val json = """
            {
                "id": "msg1",
                "content": {
                    "title": "🎉 Congratulations! مبروك",
                    "body": "你好世界 🌍"
                },
                "createdAt": "2024-01-01T00:00:00Z"
            }
        """.trimIndent()

        // When
        val message = gson.fromJson(json, InboxMessage::class.java)

        // Then
        assertEquals("🎉 Congratulations! مبروك", message.content.title)
        assertEquals("你好世界 🌍", message.content.body)
    }
}
