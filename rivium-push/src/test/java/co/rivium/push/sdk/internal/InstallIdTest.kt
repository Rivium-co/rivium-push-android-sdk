package co.rivium.push.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallIdTest {

    private val androidId = "9774d56d682e549c"
    private val pkg = "co.rivium.example"

    @Test fun `same inputs give the same value`() {
        assertEquals(InstallId.compute(androidId, pkg), InstallId.compute(androidId, pkg))
    }

    @Test fun `different package gives a different value`() {
        assertNotEquals(InstallId.compute(androidId, pkg), InstallId.compute(androidId, "co.rivium.other"))
    }

    @Test fun `different device gives a different value`() {
        assertNotEquals(InstallId.compute(androidId, pkg), InstallId.compute("0123456789abcdef", pkg))
    }

    @Test fun `missing or blank android id gives null`() {
        assertNull(InstallId.compute(null, pkg))
        assertNull(InstallId.compute("", pkg))
        assertNull(InstallId.compute("   ", pkg))
    }

    @Test fun `value is 32 lowercase hex chars`() {
        val value = InstallId.compute(androidId, pkg)!!
        assertEquals(32, value.length)
        assertTrue(value, Regex("^[0-9a-f]{32}$").matches(value))
    }

    @Test fun `raw android id never appears in the value`() {
        val value = InstallId.compute(androidId, pkg)!!
        assertFalse(value.contains(androidId))
        assertFalse(value.contains(pkg))
    }

    @Test fun `separator prevents colliding inputs`() {
        // "ab" + ":" + "cd" must differ from "abc" + ":" + "d"
        assertNotEquals(InstallId.compute("ab", "cd"), InstallId.compute("abc", "d"))
    }
}
