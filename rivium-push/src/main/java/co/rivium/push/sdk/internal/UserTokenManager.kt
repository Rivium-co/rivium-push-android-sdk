package co.rivium.push.sdk.internal

import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the current user token and refreshes it through the app's token provider.
 *
 * - Reuses the cached token until shortly before it expires, then fetches a
 *   new one before the request.
 * - Concurrent callers share one provider call.
 * - The provider runs on [executor] and is given [timeoutMs]; a provider that
 *   throws, times out or returns null never blocks a request for longer.
 * - Without a provider (e.g. the push service running without the app UI) the
 *   last token is used while it is unexpired.
 *
 * The token is opaque: only `exp` and `sub` are read, no signature check.
 * Never logs the token.
 */
internal class UserTokenManager(
    private val executor: Executor,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = PROVIDER_TIMEOUT_MS,
    private val onProviderFailed: (Throwable) -> Unit = {}
) {
    /** Where the last token is kept across process restarts. */
    interface Store {
        fun load(): String?
        fun save(token: String?)
    }

    data class Claims(val expiresAtMs: Long?, val subject: String?)

    private class Flight(val generation: Int) {
        lateinit var task: FutureTask<String?>
        val startedAtNanos: Long = System.nanoTime()
        val failureReported = AtomicBoolean(false)
    }

    private val lock = Any()
    private var token: String? = null
    private var expiresAt: Long? = null
    private var subject: String? = null
    private var provider: (() -> String?)? = null
    private var store: Store? = null
    /** False until the in-memory state is newer than whatever [store] holds. */
    private var authoritative = false
    private var flight: Flight? = null
    /** Bumped when the app changes the token or the user, so a late provider answer is dropped. */
    private var generation = 0
    private var providerCooldownUntil = 0L

    /** Set, replace or remove the provider. The cached token is kept. */
    fun setProvider(newProvider: (() -> String?)?) {
        synchronized(lock) {
            provider = newProvider
            flight = null
            providerCooldownUntil = 0L
        }
    }

    fun hasProvider(): Boolean = synchronized(lock) { provider != null }

    /** Attach persistence. The stored token is read lazily, off the caller's hot path. */
    fun attachStore(newStore: Store) {
        synchronized(lock) {
            if (store != null) return
            store = newStore
            if (authoritative) newStore.save(token)
        }
    }

    /** Use [newToken] from now on (null forgets it). For apps that fetch the token themselves. */
    fun set(newToken: String?) {
        synchronized(lock) {
            invalidate()
            apply(newToken?.takeIf { it.isNotEmpty() })
        }
    }

    /** Forget the token, in memory and on disk. */
    fun clear() = set(null)

    /** See [clearIfUnchanged]. */
    fun mark(): Int = synchronized(lock) { generation }

    /**
     * Forget the token unless the app set a token or switched user since [mark]
     * was taken. Used after the sign-out request has been sent.
     */
    fun clearIfUnchanged(mark: Int) {
        synchronized(lock) {
            if (generation != mark) return
            invalidate()
            apply(null)
        }
    }

    /**
     * Drop a cached token that belongs to a different user than [userId], so
     * the next request asks the provider again.
     */
    fun ensureSubject(userId: String) {
        synchronized(lock) {
            loadIfNeeded()
            if (token == null) {
                // A fetch that started for the previous user must not be used.
                if (flight != null) invalidate()
                return
            }
            val sub = subject
            // An unreadable subject can only be re-checked when a provider can answer again.
            val mismatch = if (sub != null) sub != userId else provider != null
            if (mismatch) {
                invalidate()
                apply(null)
            }
        }
    }

    /** The token to send, or null to send none. Blocks for at most [timeoutMs]; never throws. */
    fun get(): String? {
        val pending: Flight
        val start: Boolean
        synchronized(lock) {
            loadIfNeeded()
            if (isFresh(REFRESH_MARGIN_MS)) return token
            if (provider == null || now() < providerCooldownUntil) return unexpiredOrNull()
            val existing = flight
            start = existing == null
            pending = existing ?: newFlight()
        }
        if (start) executor.execute(pending.task)
        return await(pending, fallbackToCached = true)
    }

    /**
     * A token other than [rejected], which the server reported as expired.
     * Null when no newer token can be obtained.
     */
    fun refresh(rejected: String?): String? {
        val pending: Flight
        val start: Boolean
        synchronized(lock) {
            loadIfNeeded()
            if (token != rejected && isFresh(REFRESH_MARGIN_MS)) return token
            if (provider == null || now() < providerCooldownUntil) return null
            val existing = flight
            start = existing == null
            pending = existing ?: newFlight()
        }
        if (start) executor.execute(pending.task)
        return await(pending, fallbackToCached = false)?.takeIf { it != rejected }
    }

    // Callers hold [lock].
    private fun newFlight(): Flight {
        val fetch = provider!!
        val created = Flight(generation)
        created.task = FutureTask {
            try {
                val result = fetch()?.takeIf { it.isNotEmpty() }
                synchronized(lock) {
                    if (generation == created.generation) apply(result)
                }
                result
            } finally {
                synchronized(lock) {
                    if (flight === created) flight = null
                }
            }
        }
        flight = created
        return created
    }

    private fun await(pending: Flight, fallbackToCached: Boolean): String? {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.startedAtNanos)
        try {
            val result = pending.task.get(maxOf(0L, timeoutMs - elapsedMs), TimeUnit.MILLISECONDS)
            synchronized(lock) {
                // The app switched user or token while the provider was answering.
                return if (generation == pending.generation) result else unexpiredOrNull()
            }
        } catch (e: TimeoutException) {
            pending.task.cancel(true)
            synchronized(lock) {
                if (flight === pending) flight = null
                if (generation == pending.generation) providerCooldownUntil = now() + PROVIDER_COOLDOWN_MS
            }
            reportFailure(pending, TimeoutException("tokenProvider did not answer within ${timeoutMs}ms"))
        } catch (e: ExecutionException) {
            reportFailure(pending, e.cause ?: e)
        } catch (e: CancellationException) {
            reportFailure(pending, TimeoutException("tokenProvider did not answer within ${timeoutMs}ms"))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return if (fallbackToCached) synchronized(lock) { unexpiredOrNull() } else null
    }

    private fun reportFailure(pending: Flight, cause: Throwable) {
        if (!pending.failureReported.compareAndSet(false, true)) return
        try {
            onProviderFailed(cause)
        } catch (_: Exception) {
        }
    }

    // Callers hold [lock].
    private fun invalidate() {
        generation++
        flight = null
        providerCooldownUntil = 0L
    }

    // Callers hold [lock].
    private fun apply(newToken: String?) {
        val claims = newToken?.let { claimsOf(it) }
        token = newToken
        expiresAt = claims?.expiresAtMs
        subject = claims?.subject
        authoritative = true
        try {
            store?.save(newToken)
        } catch (_: Exception) {
        }
    }

    // Callers hold [lock].
    private fun loadIfNeeded() {
        if (authoritative) return
        val source = store ?: return
        authoritative = true
        val stored = try {
            source.load()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: return
        val claims = claimsOf(stored)
        val exp = claims.expiresAtMs
        if (exp != null && now() >= exp) {
            // An expired stored token is never sent.
            try {
                source.save(null)
            } catch (_: Exception) {
            }
            return
        }
        token = stored
        expiresAt = exp
        subject = claims.subject
    }

    // Callers hold [lock].
    private fun isFresh(marginMs: Long): Boolean {
        if (token == null) return false
        val exp = expiresAt ?: return true
        return now() < exp - marginMs
    }

    // Callers hold [lock].
    private fun unexpiredOrNull(): String? = if (isFresh(0L)) token else null

    companion object {
        /** Refresh this long before `exp`, to absorb clock skew and request time. */
        const val REFRESH_MARGIN_MS = 60_000L

        /** How long a request waits for the provider before going out without a token. */
        const val PROVIDER_TIMEOUT_MS = 10_000L

        /** After a provider timeout, requests skip the provider for this long. */
        const val PROVIDER_COOLDOWN_MS = 30_000L

        /** `exp` (in ms) and `sub` of a JWT; nulls when missing or unreadable. Never throws. */
        fun claimsOf(token: String): Claims = try {
            val payload = token.split(".").getOrNull(1)
            if (payload.isNullOrEmpty()) Claims(null, null) else {
                val json = JSONObject(decodeBase64Url(payload))
                val exp = json.optLong("exp", 0L)
                val sub = if (json.has("sub") && !json.isNull("sub")) json.optString("sub") else null
                Claims(if (exp > 0L) exp * 1000L else null, sub?.takeIf { it.isNotEmpty() })
            }
        } catch (e: Exception) {
            Claims(null, null)
        }

        /**
         * Decodes base64url with or without padding. Hand-rolled because
         * `java.util.Base64` needs API 26 (this SDK supports 21) and
         * `android.util.Base64` is unavailable in JVM unit tests.
         */
        private fun decodeBase64Url(input: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in input) {
                if (c == '=') break
                val value = alphabet.indexOf(c)
                require(value >= 0) { "not base64url" }
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
