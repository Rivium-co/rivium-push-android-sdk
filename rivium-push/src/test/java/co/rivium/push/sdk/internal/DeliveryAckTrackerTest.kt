package co.rivium.push.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryAckTrackerTest {

    @Test fun `claims a message once`() {
        val t = DeliveryAckTracker()
        assertTrue(t.tryClaim("m1"))
        assertFalse("in flight", t.tryClaim("m1"))
        t.markAcked("m1")
        assertFalse("already acked", t.tryClaim("m1"))
    }

    @Test fun `released message can be claimed again`() {
        val t = DeliveryAckTracker()
        assertTrue(t.tryClaim("m1"))
        t.release("m1")
        assertTrue(t.tryClaim("m1"))
    }

    @Test fun `empty id is never claimed`() {
        assertFalse(DeliveryAckTracker().tryClaim(""))
    }

    @Test fun `evicts oldest beyond capacity`() {
        val t = DeliveryAckTracker(capacity = 3)
        listOf("a", "b", "c", "d").forEach { t.tryClaim(it); t.markAcked(it) }
        assertEquals(3, t.size())
        assertFalse(t.contains("a"))
        assertTrue(t.tryClaim("a"))
        assertFalse(t.tryClaim("d"))
    }

    @Test fun `survives serialize and restore`() {
        val t = DeliveryAckTracker(capacity = 200)
        (1..250).forEach { t.tryClaim("m$it"); t.markAcked("m$it") }
        val restored = DeliveryAckTracker(capacity = 200).apply { restore(t.serialize()) }
        assertEquals(200, restored.size())
        assertFalse(restored.tryClaim("m250"))
        assertTrue(restored.tryClaim("m1"))
        DeliveryAckTracker().restore(null)
    }
}
