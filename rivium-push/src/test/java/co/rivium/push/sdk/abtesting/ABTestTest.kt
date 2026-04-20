package co.rivium.push.sdk.abtesting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for A/B Testing models and JSON serialization.
 */
class ABTestTest {

    // ==================== ABTestVariant Tests ====================

    @Test
    fun `ABTestVariant fromJson parses correctly`() {
        // Given
        val json = JSONObject().apply {
            put("testId", "test123")
            put("variantId", "variant_a")
            put("variantName", "Variant A")
            put("isControlGroup", false)
            put("content", JSONObject().apply {
                put("title", "New Feature")
                put("body", "Check out our new feature!")
                put("imageUrl", "https://example.com/image.png")
                put("deepLink", "app://feature")
            })
        }

        // When
        val variant = ABTestVariant.fromJson(json)

        // Then
        assertEquals("test123", variant.testId)
        assertEquals("variant_a", variant.variantId)
        assertEquals("Variant A", variant.variantName)
        assertFalse(variant.isControlGroup)
        assertNotNull(variant.content)
        assertEquals("New Feature", variant.content?.title)
        assertEquals("Check out our new feature!", variant.content?.body)
        assertEquals("https://example.com/image.png", variant.content?.imageUrl)
        assertEquals("app://feature", variant.content?.deepLink)
    }

    @Test
    fun `ABTestVariant fromJson handles control group`() {
        // Given
        val json = JSONObject().apply {
            put("testId", "test123")
            put("variantId", "control")
            put("variantName", "Control")
            put("isControlGroup", true)
        }

        // When
        val variant = ABTestVariant.fromJson(json)

        // Then
        assertTrue(variant.isControlGroup)
        assertNull(variant.content)
    }

    @Test
    fun `ABTestVariant fromJson handles missing optional fields`() {
        // Given
        val json = JSONObject().apply {
            put("testId", "test123")
            put("variantId", "variant_a")
            put("variantName", "Variant A")
            // isControlGroup not set - should default to false
            // content not set - should be null
        }

        // When
        val variant = ABTestVariant.fromJson(json)

        // Then
        assertFalse(variant.isControlGroup)
        assertNull(variant.content)
    }

    @Test
    fun `ABTestVariant toJson serializes correctly`() {
        // Given
        val content = ABTestContent(
            title = "Title",
            body = "Body",
            imageUrl = "https://img.com/1.png"
        )
        val variant = ABTestVariant(
            testId = "test1",
            variantId = "var1",
            variantName = "Variant 1",
            isControlGroup = false,
            content = content
        )

        // When
        val json = variant.toJson()

        // Then
        assertEquals("test1", json.getString("testId"))
        assertEquals("var1", json.getString("variantId"))
        assertEquals("Variant 1", json.getString("variantName"))
        assertFalse(json.getBoolean("isControlGroup"))
        assertTrue(json.has("content"))
        assertEquals("Title", json.getJSONObject("content").getString("title"))
    }

    @Test
    fun `ABTestVariant toJson without content`() {
        // Given
        val variant = ABTestVariant(
            testId = "test1",
            variantId = "control",
            variantName = "Control",
            isControlGroup = true,
            content = null
        )

        // When
        val json = variant.toJson()

        // Then
        assertFalse(json.has("content"))
    }

    // ==================== ABTestContent Tests ====================

    @Test
    fun `ABTestContent fromJson parses all fields`() {
        // Given
        val json = JSONObject().apply {
            put("title", "Welcome")
            put("body", "Welcome to the app!")
            put("imageUrl", "https://img.com/welcome.png")
            put("deepLink", "app://welcome")
            put("data", JSONObject().apply {
                put("customKey", "customValue")
                put("number", 42)
            })
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "action1")
                    put("title", "Learn More")
                    put("action", "https://example.com/learn")
                })
            })
        }

        // When
        val content = ABTestContent.fromJson(json)

        // Then
        assertEquals("Welcome", content.title)
        assertEquals("Welcome to the app!", content.body)
        assertEquals("https://img.com/welcome.png", content.imageUrl)
        assertEquals("app://welcome", content.deepLink)
        assertNotNull(content.data)
        assertEquals("customValue", content.data?.get("customKey"))
        assertEquals(42, content.data?.get("number"))
        assertNotNull(content.actions)
        assertEquals(1, content.actions?.size)
        assertEquals("action1", content.actions?.get(0)?.id)
    }

    @Test
    fun `ABTestContent fromJson handles missing optional fields`() {
        // Given
        val json = JSONObject().apply {
            put("title", "Simple")
            put("body", "Simple content")
        }

        // When
        val content = ABTestContent.fromJson(json)

        // Then
        assertEquals("Simple", content.title)
        assertEquals("Simple content", content.body)
        assertNull(content.imageUrl)
        assertNull(content.deepLink)
        assertNull(content.data)
        assertNull(content.actions)
    }

    @Test
    fun `ABTestContent fromJson uses defaults for missing required fields`() {
        // Given
        val json = JSONObject() // Empty object

        // When
        val content = ABTestContent.fromJson(json)

        // Then
        assertEquals("", content.title)
        assertEquals("", content.body)
    }

    @Test
    fun `ABTestContent toJson serializes correctly`() {
        // Given
        val action = ABTestAction("btn1", "Click Me", "https://example.com")
        val content = ABTestContent(
            title = "Test",
            body = "Test body",
            data = mapOf("key" to "value"),
            imageUrl = "https://img.com/1.png",
            deepLink = "app://test",
            actions = listOf(action)
        )

        // When
        val json = content.toJson()

        // Then
        assertEquals("Test", json.getString("title"))
        assertEquals("Test body", json.getString("body"))
        assertEquals("https://img.com/1.png", json.getString("imageUrl"))
        assertEquals("app://test", json.getString("deepLink"))
        assertTrue(json.has("data"))
        assertEquals("value", json.getJSONObject("data").getString("key"))
        assertTrue(json.has("actions"))
        assertEquals(1, json.getJSONArray("actions").length())
    }

    // ==================== ABTestAction Tests ====================

    @Test
    fun `ABTestAction fromJson parses correctly`() {
        // Given
        val json = JSONObject().apply {
            put("id", "action123")
            put("title", "Open Link")
            put("action", "https://example.com/page")
        }

        // When
        val action = ABTestAction.fromJson(json)

        // Then
        assertEquals("action123", action.id)
        assertEquals("Open Link", action.title)
        assertEquals("https://example.com/page", action.action)
    }

    @Test
    fun `ABTestAction toJson serializes correctly`() {
        // Given
        val action = ABTestAction(
            id = "btn1",
            title = "Subscribe",
            action = "app://subscribe"
        )

        // When
        val json = action.toJson()

        // Then
        assertEquals("btn1", json.getString("id"))
        assertEquals("Subscribe", json.getString("title"))
        assertEquals("app://subscribe", json.getString("action"))
    }

    // ==================== ABTestSummary Tests ====================

    @Test
    fun `ABTestSummary fromJson parses correctly`() {
        // Given
        val json = JSONObject().apply {
            put("id", "test123")
            put("name", "Button Color Test")
            put("variantCount", 3)
            put("hasControlGroup", true)
        }

        // When
        val summary = ABTestSummary.fromJson(json)

        // Then
        assertEquals("test123", summary.id)
        assertEquals("Button Color Test", summary.name)
        assertEquals(3, summary.variantCount)
        assertTrue(summary.hasControlGroup)
    }

    @Test
    fun `ABTestSummary fromJson handles defaults`() {
        // Given
        val json = JSONObject().apply {
            put("id", "test1")
            put("name", "Test")
            // variantCount and hasControlGroup not set
        }

        // When
        val summary = ABTestSummary.fromJson(json)

        // Then
        assertEquals(0, summary.variantCount)
        assertFalse(summary.hasControlGroup)
    }

    // ==================== ABTestStatistics Tests ====================

    @Test
    fun `ABTestStatistics fromJson parses all fields`() {
        // Given
        val json = JSONObject().apply {
            put("isSignificant", true)
            put("confidenceLevel", 0.95)
            put("pValue", 0.023)
            put("lift", 15.5)
            put("sampleSizeRecommendation", 1000)
        }

        // When
        val stats = ABTestStatistics.fromJson(json)

        // Then
        assertTrue(stats.isSignificant)
        assertEquals(0.95, stats.confidenceLevel, 0.001)
        assertEquals(0.023, stats.pValue, 0.001)
        assertEquals(15.5, stats.lift, 0.1)
        assertEquals(1000, stats.sampleSizeRecommendation)
    }

    @Test
    fun `ABTestStatistics fromJson handles missing optional fields`() {
        // Given
        val json = JSONObject().apply {
            put("isSignificant", false)
            put("confidenceLevel", 0.80)
            put("pValue", 0.15)
            put("lift", 2.3)
            // sampleSizeRecommendation not set
        }

        // When
        val stats = ABTestStatistics.fromJson(json)

        // Then
        assertNull(stats.sampleSizeRecommendation)
    }

    @Test
    fun `ABTestStatistics toJson serializes correctly`() {
        // Given
        val stats = ABTestStatistics(
            isSignificant = true,
            confidenceLevel = 0.99,
            pValue = 0.001,
            lift = 25.0,
            sampleSizeRecommendation = 500
        )

        // When
        val json = stats.toJson()

        // Then
        assertTrue(json.getBoolean("isSignificant"))
        assertEquals(0.99, json.getDouble("confidenceLevel"), 0.001)
        assertEquals(0.001, json.getDouble("pValue"), 0.0001)
        assertEquals(25.0, json.getDouble("lift"), 0.1)
        assertEquals(500, json.getInt("sampleSizeRecommendation"))
    }

    // ==================== ConfidenceInterval Tests ====================

    @Test
    fun `ConfidenceInterval fromJson parses correctly`() {
        // Given
        val json = JSONObject().apply {
            put("lower", 0.10)
            put("upper", 0.25)
        }

        // When
        val interval = ConfidenceInterval.fromJson(json)

        // Then
        assertEquals(0.10, interval.lower, 0.001)
        assertEquals(0.25, interval.upper, 0.001)
    }

    @Test
    fun `ConfidenceInterval toJson serializes correctly`() {
        // Given
        val interval = ConfidenceInterval(lower = 0.05, upper = 0.15)

        // When
        val json = interval.toJson()

        // Then
        assertEquals(0.05, json.getDouble("lower"), 0.001)
        assertEquals(0.15, json.getDouble("upper"), 0.001)
    }

    // ==================== ABTestVariantStats Tests ====================

    @Test
    fun `ABTestVariantStats fromJson parses all fields`() {
        // Given
        val json = JSONObject().apply {
            put("id", "variant_a")
            put("name", "Variant A")
            put("isControlGroup", false)
            put("trafficPercentage", 50)
            put("sentCount", 1000)
            put("deliveredCount", 980)
            put("openedCount", 250)
            put("clickedCount", 100)
            put("convertedCount", 50)
            put("failedCount", 20)
            put("deliveryRate", 0.98)
            put("openRate", 0.255)
            put("clickRate", 0.102)
            put("conversionRate", 0.051)
            put("confidenceInterval", JSONObject().apply {
                put("lower", 0.08)
                put("upper", 0.12)
            })
            put("improvementVsControl", 15.5)
            put("isSignificantVsControl", true)
            put("pValueVsControl", 0.02)
        }

        // When
        val stats = ABTestVariantStats.fromJson(json)

        // Then
        assertEquals("variant_a", stats.id)
        assertEquals("Variant A", stats.name)
        assertFalse(stats.isControlGroup)
        assertEquals(50, stats.trafficPercentage)
        assertEquals(1000, stats.sentCount)
        assertEquals(980, stats.deliveredCount)
        assertEquals(250, stats.openedCount)
        assertEquals(100, stats.clickedCount)
        assertEquals(50, stats.convertedCount)
        assertEquals(20, stats.failedCount)
        assertEquals(0.98, stats.deliveryRate, 0.001)
        assertEquals(0.255, stats.openRate, 0.001)
        assertEquals(0.102, stats.clickRate, 0.001)
        assertEquals(0.051, stats.conversionRate, 0.001)
        assertNotNull(stats.confidenceInterval)
        assertEquals(0.08, stats.confidenceInterval?.lower ?: 0.0, 0.001)
        assertEquals(0.12, stats.confidenceInterval?.upper ?: 0.0, 0.001)
        assertEquals(15.5, stats.improvementVsControl ?: 0.0, 0.1)
        assertTrue(stats.isSignificantVsControl ?: false)
        assertEquals(0.02, stats.pValueVsControl ?: 0.0, 0.001)
    }

    @Test
    fun `ABTestVariantStats fromJson handles control group without comparison stats`() {
        // Given
        val json = JSONObject().apply {
            put("id", "control")
            put("name", "Control")
            put("isControlGroup", true)
            put("trafficPercentage", 50)
            put("sentCount", 1000)
            put("deliveredCount", 950)
            put("openedCount", 200)
            put("clickedCount", 80)
            put("failedCount", 50)
            put("deliveryRate", 0.95)
            put("openRate", 0.21)
            put("clickRate", 0.084)
            // No comparison stats for control group
        }

        // When
        val stats = ABTestVariantStats.fromJson(json)

        // Then
        assertTrue(stats.isControlGroup)
        assertNull(stats.confidenceInterval)
        assertNull(stats.improvementVsControl)
        assertNull(stats.isSignificantVsControl)
        assertNull(stats.pValueVsControl)
    }

    @Test
    fun `ABTestVariantStats toJson serializes correctly`() {
        // Given
        val stats = ABTestVariantStats(
            id = "var1",
            name = "Variant 1",
            isControlGroup = false,
            trafficPercentage = 33,
            sentCount = 500,
            deliveredCount = 490,
            openedCount = 100,
            clickedCount = 40,
            convertedCount = 20,
            failedCount = 10,
            deliveryRate = 0.98,
            openRate = 0.20,
            clickRate = 0.08,
            conversionRate = 0.04,
            confidenceInterval = ConfidenceInterval(0.06, 0.10),
            improvementVsControl = 10.0,
            isSignificantVsControl = false,
            pValueVsControl = 0.08
        )

        // When
        val json = stats.toJson()

        // Then
        assertEquals("var1", json.getString("id"))
        assertEquals("Variant 1", json.getString("name"))
        assertEquals(33, json.getInt("trafficPercentage"))
        assertEquals(500, json.getInt("sentCount"))
        assertTrue(json.has("confidenceInterval"))
        assertEquals(10.0, json.getDouble("improvementVsControl"), 0.1)
    }

    // ==================== ABTestEvent Tests ====================

    @Test
    fun `ABTestEvent has correct values`() {
        assertEquals("impression", ABTestEvent.IMPRESSION.value)
        assertEquals("opened", ABTestEvent.OPENED.value)
        assertEquals("clicked", ABTestEvent.CLICKED.value)
        assertEquals("converted", ABTestEvent.CONVERTED.value)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `ABTestVariant handles null content in JSON`() {
        // Given
        val json = JSONObject().apply {
            put("testId", "test1")
            put("variantId", "var1")
            put("variantName", "Variant 1")
            put("content", JSONObject.NULL)
        }

        // When
        val variant = ABTestVariant.fromJson(json)

        // Then
        assertNull(variant.content)
    }

    @Test
    fun `ABTestContent handles empty actions array`() {
        // Given
        val json = JSONObject().apply {
            put("title", "Test")
            put("body", "Body")
            put("actions", JSONArray())
        }

        // When
        val content = ABTestContent.fromJson(json)

        // Then
        assertNotNull(content.actions)
        assertTrue(content.actions?.isEmpty() == true)
    }

    @Test
    fun `ABTestContent handles multiple actions`() {
        // Given
        val json = JSONObject().apply {
            put("title", "Test")
            put("body", "Body")
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "1")
                    put("title", "Action 1")
                    put("action", "url1")
                })
                put(JSONObject().apply {
                    put("id", "2")
                    put("title", "Action 2")
                    put("action", "url2")
                })
                put(JSONObject().apply {
                    put("id", "3")
                    put("title", "Action 3")
                    put("action", "url3")
                })
            })
        }

        // When
        val content = ABTestContent.fromJson(json)

        // Then
        assertEquals(3, content.actions?.size)
        assertEquals("Action 1", content.actions?.get(0)?.title)
        assertEquals("Action 2", content.actions?.get(1)?.title)
        assertEquals("Action 3", content.actions?.get(2)?.title)
    }

    @Test
    fun `roundtrip serialization preserves data`() {
        // Given
        val original = ABTestVariant(
            testId = "test123",
            variantId = "variant_a",
            variantName = "Variant A",
            isControlGroup = false,
            content = ABTestContent(
                title = "Hello",
                body = "World",
                imageUrl = "https://img.com/1.png",
                deepLink = "app://home",
                data = mapOf("key1" to "value1", "key2" to 42),
                actions = listOf(
                    ABTestAction("btn1", "Click", "https://click.com")
                )
            )
        )

        // When
        val json = original.toJson()
        val restored = ABTestVariant.fromJson(json)

        // Then
        assertEquals(original.testId, restored.testId)
        assertEquals(original.variantId, restored.variantId)
        assertEquals(original.variantName, restored.variantName)
        assertEquals(original.isControlGroup, restored.isControlGroup)
        assertEquals(original.content?.title, restored.content?.title)
        assertEquals(original.content?.body, restored.content?.body)
        assertEquals(original.content?.imageUrl, restored.content?.imageUrl)
        assertEquals(original.content?.deepLink, restored.content?.deepLink)
        assertEquals(original.content?.actions?.size, restored.content?.actions?.size)
    }
}
