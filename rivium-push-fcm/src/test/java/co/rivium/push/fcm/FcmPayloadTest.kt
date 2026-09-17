package co.rivium.push.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPayloadTest {

    @Test fun `extracts rivium payload`() {
        val json = """{"messageId":"m1","title":"t","body":"b"}"""
        assertEquals(json, FcmPayload.extract(mapOf("rivium" to json, "truncated" to "1")))
    }

    @Test fun `non rivium messages are ignored`() {
        assertNull(FcmPayload.extract(null))
        assertNull(FcmPayload.extract(emptyMap()))
        assertNull(FcmPayload.extract(mapOf("other" to "x")))
        assertNull(FcmPayload.extract(mapOf("rivium" to " ")))
    }

    @Test fun `truncated flag`() {
        assertTrue(FcmPayload.isTruncated(mapOf("truncated" to "1")))
        assertFalse(FcmPayload.isTruncated(mapOf("truncated" to "0")))
        assertFalse(FcmPayload.isTruncated(null))
    }

    @Test fun `register fields only with a token`() {
        val none = mutableMapOf<String, Any?>()
        FcmPayload.registerFields(null, "proj", none)
        FcmPayload.registerFields("", "proj", none)
        assertTrue(none.isEmpty())

        val set = mutableMapOf<String, Any?>()
        FcmPayload.registerFields("tok", "proj", set)
        assertEquals(mapOf("fcmToken" to "tok", "fcmProjectId" to "proj"), set)

        val noProject = mutableMapOf<String, Any?>()
        FcmPayload.registerFields("tok", null, noProject)
        assertEquals(mapOf<String, Any?>("fcmToken" to "tok"), noProject)
    }
}
