package co.rivium.push.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationRefreshPolicyTest {

    private val day = RegistrationRefreshPolicy.REFRESH_INTERVAL_MS
    private val sdk = SdkIdentity("android", "0.1.12")
    private val fp = RegistrationRefreshPolicy.fingerprint("1.0", 10L, sdk, "user-1")
    private val now = 1_800_000_000_000L

    private fun decide(
        registered: Boolean = true,
        lastAt: Long = now - 1000,
        stored: String? = fp,
        current: String = fp,
        nowMs: Long = now
    ) = RegistrationRefreshPolicy.decide(registered, lastAt, nowMs, stored, current)

    @Test fun `never refreshes an install that never registered`() {
        assertNull(decide(registered = false, lastAt = 0, stored = null, current = "x"))
    }

    @Test fun `skips when fresh and unchanged`() {
        assertNull(decide(lastAt = now - day + 1))
    }

    @Test fun `refreshes after 24h`() {
        assertEquals(RegistrationRefreshPolicy.Reason.STALE, decide(lastAt = now - day))
    }

    @Test fun `refreshes when timestamp unknown or clock went backwards`() {
        assertEquals(RegistrationRefreshPolicy.Reason.STALE, decide(lastAt = 0))
        assertEquals(RegistrationRefreshPolicy.Reason.STALE, decide(lastAt = now + 60_000))
    }

    @Test fun `refreshes legacy installs without a stored fingerprint`() {
        assertEquals(RegistrationRefreshPolicy.Reason.FINGERPRINT_CHANGED, decide(lastAt = 0, stored = null))
    }

    @Test fun `app version, build, sdk and userId changes all change the fingerprint`() {
        val f = RegistrationRefreshPolicy::fingerprint
        assertNotEquals(fp, f("1.1", 10L, sdk, "user-1"))
        assertNotEquals(fp, f("1.0", 11L, sdk, "user-1"))
        assertNotEquals(fp, f("1.0", 10L, SdkIdentity("android", "0.1.13"), "user-1"))
        assertNotEquals(fp, f("1.0", 10L, SdkIdentity("flutter", "0.1.12"), "user-1"))
        assertNotEquals(fp, f("1.0", 10L, sdk, "user-2"))
        assertNotEquals(fp, f("1.0", 10L, sdk, null))
        assertEquals(fp, f("1.0", 10L, sdk, "user-1"))
        assertEquals(
            RegistrationRefreshPolicy.Reason.FINGERPRINT_CHANGED,
            decide(current = f("1.1", 10L, sdk, "user-1"))
        )
    }
}
