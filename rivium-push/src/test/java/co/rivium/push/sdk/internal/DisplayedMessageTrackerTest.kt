package co.rivium.push.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayedMessageTrackerTest {

    @Test fun `second copy of a message is rejected`() {
        val t = DisplayedMessageTracker()
        assertTrue(t.tryClaim("m1"))
        assertFalse("duplicate", t.tryClaim("m1"))
        assertTrue(t.tryClaim("m2"))
    }

    @Test fun `messages without id are never deduplicated`() {
        val t = DisplayedMessageTracker()
        assertTrue(t.tryClaim(null))
        assertTrue(t.tryClaim(null))
        assertTrue(t.tryClaim(""))
        assertTrue(t.tryClaim(""))
        assertEquals(0, t.size())
    }

    @Test fun `evicts oldest beyond capacity`() {
        val t = DisplayedMessageTracker(capacity = 3)
        listOf("a", "b", "c", "d").forEach { t.tryClaim(it) }
        assertEquals(3, t.size())
        assertFalse(t.contains("a"))
        assertTrue(t.tryClaim("a"))
        assertFalse(t.tryClaim("d"))
    }

    @Test fun `default capacity is 500`() {
        val t = DisplayedMessageTracker()
        (1..600).forEach { t.tryClaim("m$it") }
        assertEquals(500, t.size())
        assertFalse(t.contains("m100"))
        assertTrue(t.contains("m101"))
    }

    @Test fun `survives serialize and restore`() {
        val t = DisplayedMessageTracker(capacity = 200)
        (1..250).forEach { t.tryClaim("m$it") }
        val restored = DisplayedMessageTracker(capacity = 200).apply { restore(t.serialize()) }
        assertEquals(200, restored.size())
        assertFalse(restored.tryClaim("m250"))
        assertTrue(restored.tryClaim("m1"))
        DisplayedMessageTracker().restore(null)
        DisplayedMessageTracker().restore("")
    }
}
