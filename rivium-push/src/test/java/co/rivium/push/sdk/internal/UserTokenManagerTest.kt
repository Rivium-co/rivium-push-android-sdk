package co.rivium.push.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UserTokenManagerTest {

    private var nowMs = 1_700_000_000_000L
    private val failures = mutableListOf<Throwable>()
    private val pool = Executors.newCachedThreadPool()

    private fun manager(timeoutMs: Long = 2_000L, executor: Executor = pool) = UserTokenManager(
        executor = executor,
        now = { nowMs },
        timeoutMs = timeoutMs,
        onProviderFailed = { synchronized(failures) { failures.add(it) } }
    )

    private class MemoryStore(var value: String? = null) : UserTokenManager.Store {
        var saves = 0
        override fun load(): String? = value
        override fun save(token: String?) {
            saves++
            value = token
        }
    }

    private fun jwt(sub: String? = "user-1", expInSeconds: Long? = 3600, tag: String = "a"): String {
        val fields = mutableListOf<String>()
        if (sub != null) fields.add("\"sub\":\"$sub\"")
        if (expInSeconds != null) fields.add("\"exp\":${nowMs / 1000 + expInSeconds}")
        fields.add("\"pid\":\"p1\"")
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString("{${fields.joinToString(",")}}".toByteArray())
        return "eyJhbGciOiJFUzI1NiJ9.$payload.sig$tag"
    }

    // ==================== Cache ====================

    @Test
    fun `get returns null without provider or token`() {
        assertNull(manager().get())
    }

    @Test
    fun `token is cached and the provider called once`() {
        val calls = AtomicInteger()
        val token = jwt()
        val m = manager()
        m.setProvider { calls.incrementAndGet(); token }

        assertEquals(token, m.get())
        assertEquals(token, m.get())
        assertEquals(1, calls.get())
    }

    @Test
    fun `token is reused until 60 seconds before exp then fetched again`() {
        val calls = AtomicInteger()
        val first = jwt(expInSeconds = 600, tag = "1")
        val second = jwt(expInSeconds = 4200, tag = "2")
        val m = manager()
        m.setProvider { if (calls.incrementAndGet() == 1) first else second }

        assertEquals(first, m.get())
        nowMs += (600 - 61) * 1000L
        assertEquals(first, m.get())
        assertEquals(1, calls.get())

        nowMs += 2_000L // 59 s before exp
        assertEquals(second, m.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun `token without exp is reused until told otherwise`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { calls.incrementAndGet(); jwt(expInSeconds = null) }

        m.get()
        nowMs += 365L * 24 * 3600 * 1000
        m.get()
        assertEquals(1, calls.get())
    }

    @Test
    fun `opaque non-JWT token is cached like a token without exp`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { calls.incrementAndGet(); "opaque-token" }

        assertEquals("opaque-token", m.get())
        assertEquals("opaque-token", m.get())
        assertEquals(1, calls.get())
    }

    // ==================== Shared in-flight ====================

    @Test
    fun `concurrent callers share one provider call`() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val token = jwt()
        val m = manager(timeoutMs = 5_000L)
        m.setProvider {
            calls.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            token
        }

        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                results.add(m.get())
                done.countDown()
            }.start()
        }
        Thread.sleep(200)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(1, calls.get())
        assertEquals(8, results.size)
        assertTrue(results.all { it == token })
    }

    // ==================== Refresh / clear ====================

    @Test
    fun `refresh forces a new fetch`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { jwt(tag = calls.incrementAndGet().toString()) }

        val first = m.get()
        val second = m.refresh(rejected = first)
        assertEquals(2, calls.get())
        assertTrue(second != null && second != first)
        assertEquals(second, m.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun `refresh reuses a token another caller already refreshed`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { jwt(tag = calls.incrementAndGet().toString()) }

        val first = m.get()
        val second = m.refresh(rejected = first)
        assertEquals(second, m.refresh(rejected = first))
        assertEquals(2, calls.get())
    }

    @Test
    fun `refresh without provider returns null`() {
        val m = manager()
        val token = jwt()
        m.set(token)
        assertNull(m.refresh(rejected = token))
    }

    @Test
    fun `clear forgets the token`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { calls.incrementAndGet(); jwt() }

        m.get()
        m.clear()
        m.get()
        assertEquals(2, calls.get())
    }

    @Test
    fun `set uses a token without provider and null forgets it`() {
        val m = manager()
        val token = jwt()
        m.set(token)
        assertEquals(token, m.get())
        m.set(null)
        assertNull(m.get())
    }

    @Test
    fun `manually set token is not sent once expired`() {
        val m = manager()
        m.set(jwt(expInSeconds = 30))
        assertTrue(m.get() != null) // inside the margin but still valid
        nowMs += 31_000L
        assertNull(m.get())
    }

    // ==================== Provider failures ====================

    @Test
    fun `provider returning null means signed out - no token and no failure`() {
        val m = manager()
        m.setProvider { null }
        assertNull(m.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `provider returning empty string is treated as no token`() {
        val m = manager()
        m.setProvider { "" }
        assertNull(m.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `provider that throws yields no token and reports the failure`() {
        val m = manager()
        m.setProvider { throw IllegalStateException("backend down") }
        assertNull(m.get())
        assertEquals(1, failures.size)
        assertEquals("backend down", failures[0].message)
    }

    @Test
    fun `provider failure falls back to a cached token that is still valid`() {
        val calls = AtomicInteger()
        val token = jwt(expInSeconds = 600)
        val m = manager()
        m.setProvider { if (calls.incrementAndGet() == 1) token else throw RuntimeException("boom") }

        assertEquals(token, m.get())
        nowMs += 570_000L // inside the 60 s margin, not expired
        assertEquals(token, m.get())
        assertEquals(1, failures.size)

        nowMs += 31_000L // expired
        assertNull(m.get())
    }

    @Test
    fun `provider that hangs times out, reports once and is skipped for a while`() {
        val calls = AtomicInteger()
        val m = manager(timeoutMs = 300L)
        m.setProvider {
            calls.incrementAndGet()
            Thread.sleep(5_000L)
            jwt()
        }

        val started = System.nanoTime()
        assertNull(m.get())
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("took ${tookMs}ms", tookMs < 2_000L)
        assertEquals(1, failures.size)

        // Cool-down: the next request does not wait for the provider again.
        assertNull(m.get())
        assertEquals(1, calls.get())

        nowMs += UserTokenManager.PROVIDER_COOLDOWN_MS + 1
        assertNull(m.get())
        assertEquals(2, calls.get())
    }

    // ==================== User changes ====================

    @Test
    fun `ensureSubject drops a token of another user and keeps a matching one`() {
        val calls = AtomicInteger()
        val m = manager()
        m.setProvider { if (calls.incrementAndGet() == 1) jwt(sub = "alice") else jwt(sub = "bob") }

        m.get()
        m.ensureSubject("alice")
        m.get()
        assertEquals(1, calls.get())

        m.ensureSubject("bob")
        assertEquals("bob", UserTokenManager.claimsOf(m.get()!!).subject)
        assertEquals(2, calls.get())
    }

    @Test
    fun `ensureSubject keeps a manual token whose subject cannot be read`() {
        val m = manager()
        m.set("opaque-token")
        m.ensureSubject("alice")
        assertEquals("opaque-token", m.get())
    }

    @Test
    fun `ensureSubject drops a manual token of another user`() {
        val m = manager()
        m.set(jwt(sub = "alice"))
        m.ensureSubject("bob")
        assertNull(m.get())
    }

    @Test
    fun `clearIfUnchanged clears unless the app set a newer token`() {
        val m = manager()
        m.set(jwt(sub = "alice"))
        val mark = m.mark()
        m.clearIfUnchanged(mark)
        assertNull(m.get())

        m.set(jwt(sub = "alice"))
        val staleMark = m.mark()
        val bob = jwt(sub = "bob")
        m.set(bob)
        m.clearIfUnchanged(staleMark)
        assertEquals(bob, m.get())
    }

    @Test
    fun `a provider answer that arrives after a user switch is not used`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val m = manager(timeoutMs = 5_000L)
        m.setProvider {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                jwt(sub = "alice")
            } else {
                jwt(sub = "bob")
            }
        }

        var first: String? = "unset"
        val t = Thread { first = m.get() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        m.ensureSubject("bob")
        release.countDown()
        t.join(5_000L)

        assertNull(first)
        assertEquals("bob", UserTokenManager.claimsOf(m.get()!!).subject)
    }

    // ==================== Persistence ====================

    @Test
    fun `tokens are persisted and cleared in the store`() {
        val store = MemoryStore()
        val token = jwt()
        val m = manager()
        m.attachStore(store)
        m.setProvider { token }

        m.get()
        assertEquals(token, store.value)
        m.clear()
        assertNull(store.value)
    }

    @Test
    fun `persisted unexpired token is used when no provider is registered`() {
        val token = jwt(expInSeconds = 30)
        val m = manager()
        m.attachStore(MemoryStore(token))
        assertEquals(token, m.get())
    }

    @Test
    fun `persisted expired token is not sent and is removed`() {
        val store = MemoryStore(jwt(expInSeconds = -5))
        val m = manager()
        m.attachStore(store)
        assertNull(m.get())
        assertNull(store.value)
    }

    @Test
    fun `token set before the store is attached is persisted on attach`() {
        val store = MemoryStore("old")
        val token = jwt()
        val m = manager()
        m.set(token)
        m.attachStore(store)
        assertEquals(token, store.value)
        assertEquals(token, m.get())
    }

    // ==================== JWT parsing ====================

    @Test
    fun `claimsOf reads exp and sub`() {
        val claims = UserTokenManager.claimsOf(jwt(sub = "user-9", expInSeconds = 100))
        assertEquals("user-9", claims.subject)
        assertEquals((nowMs / 1000 + 100) * 1000, claims.expiresAtMs)
    }

    @Test
    fun `claimsOf accepts padded base64url`() {
        val payload = Base64.getUrlEncoder().encodeToString("{\"sub\":\"u\",\"exp\":2000000000}".toByteArray())
        val claims = UserTokenManager.claimsOf("h.$payload.s")
        assertEquals("u", claims.subject)
        assertEquals(2_000_000_000_000L, claims.expiresAtMs)
    }

    @Test
    fun `claimsOf never throws on bad input`() {
        val bad = listOf("", "garbage", "a.b", "a.!!!.c", "a..c", "...", "a.e30.c", "a.bm90IGpzb24.c", "\u0000.\u0000.\u0000")
        for (input in bad) {
            val claims = UserTokenManager.claimsOf(input)
            assertNull(input, claims.expiresAtMs)
            assertNull(input, claims.subject)
        }
    }
}
