package co.rivium.push.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import co.rivium.push.sdk.Log
import co.rivium.push.sdk.RiviumPushErrorCode
import okhttp3.Interceptor

/**
 * SDK self-reports. The SDK counts its own errors by the public
 * [RiviumPushErrorCode] number every Rivium Push SDK shares, and sends the counts with its next ordinary API
 * request in one header:
 *
 *     X-Rivium-SDK-Report: 1000=3 1400=1 9100=1
 *
 * Counts only — never a message, stack trace, key, user or device detail. A count is cleared only after the
 * server answered 2xx, so nothing is lost while offline and nothing is counted twice. Switched off with
 * [co.rivium.push.sdk.RiviumPushConfig.sendDiagnostics] = false: then nothing is counted, kept or sent.
 *
 * [CRASH] (report-only): the app crashed in Rivium Push code (see [isOwnCrash]). Seen by a chained default
 * uncaught-exception handler that counts it and always hands the crash on to the handler that was there before
 * (the app's crash reporter or Android's), the way crash reporters chain.
 */
internal object SdkDiagnostics {
    private const val TAG = "SdkDiagnostics"
    const val HEADER = "X-Rivium-SDK-Report"

    /** Report-only code: an uncaught exception thrown by Rivium Push code. */
    const val CRASH = 9100

    /** Most codes per report and highest count per code (the server accepts no more). */
    const val MAX_CODES = 16
    const val MAX_COUNT = 9999

    private const val PREFS = "rivium_push_diagnostics"
    private const val KEY_PREFIX = "c_"
    private val OWN_PACKAGES = listOf("co.rivium.push.", "co.rivium.protocol.")

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var enabled = true
    @Volatile private var crashHandlerInstalled = false

    /** Counted before [attach] (e.g. while the service starts); written once storage is there. */
    private val early = mutableMapOf<Int, Int>()

    /** Call from init() and the service. Safe to call more than once; the last `enabled` wins. */
    fun attach(context: Context, enabled: Boolean) {
        try {
            synchronized(lock) {
                this.enabled = enabled
                val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs = p
                if (!enabled) {
                    early.clear()
                    p.edit().clear().apply()
                    return
                }
                if (early.isNotEmpty()) {
                    val edit = p.edit()
                    for ((code, n) in early) edit.putInt(KEY_PREFIX + code, add(p.getInt(KEY_PREFIX + code, 0), n))
                    edit.apply()
                    early.clear()
                }
            }
            installCrashHandler()
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostics not attached: ${e.message}")
        }
    }

    fun count(code: RiviumPushErrorCode) = count(code.code)

    /** Adds one. Never throws; a 17th distinct code is dropped. */
    fun count(code: Int) {
        if (!enabled) return
        try {
            synchronized(lock) {
                val p = prefs
                if (p == null) {
                    if (early.containsKey(code) || early.size < MAX_CODES) early[code] = add(early[code] ?: 0, 1)
                    return
                }
                val key = KEY_PREFIX + code
                if (!p.contains(key) && pendingLocked().size >= MAX_CODES) return
                p.edit().putInt(key, add(p.getInt(key, 0), 1)).apply()
            }
        } catch (_: Exception) {
            // Counting never matters more than the SDK's work.
        }
    }

    /** What would be sent now: code → count. */
    fun pending(): Map<Int, Int> = synchronized(lock) { if (enabled) pendingLocked() else emptyMap() }

    /** `1000=3 1400=1`, or null when there is nothing to send. Codes in ascending order. */
    fun headerValue(snapshot: Map<Int, Int>): String? =
        if (snapshot.isEmpty()) null
        else snapshot.entries.sortedBy { it.key }.take(MAX_CODES).joinToString(" ") { "${it.key}=${it.value.coerceIn(1, MAX_COUNT)}" }

    /** The server took [snapshot]: subtract it (counts added meanwhile stay for the next request). */
    fun acknowledge(snapshot: Map<Int, Int>) {
        try {
            synchronized(lock) {
                val p = prefs ?: return
                val edit = p.edit()
                for ((code, sent) in snapshot) {
                    val left = p.getInt(KEY_PREFIX + code, 0) - sent
                    if (left > 0) edit.putInt(KEY_PREFIX + code, left) else edit.remove(KEY_PREFIX + code)
                }
                edit.apply()
            }
        } catch (_: Exception) {
            // Sent again next time; a count a little high is better than a crash.
        }
    }

    /** OkHttp interceptor: attaches the report and clears what a 2xx answer took. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val snapshot = pending()
        val value = headerValue(snapshot)
        if (value == null) {
            chain.proceed(chain.request())
        } else {
            val response = chain.proceed(chain.request().newBuilder().header(HEADER, value).build())
            if (response.isSuccessful) acknowledge(snapshot)
            response
        }
    }

    /**
     * True when the code that threw is Rivium Push code: in the root cause, the first frame that is not platform or
     * library code belongs to Rivium Push. An app callback that throws while the SDK calls it is the app's crash,
     * not the SDK's (the SDK is only further down the stack).
     */
    fun isOwnCrash(error: Throwable): Boolean {
        var root = error
        var depth = 0
        while (depth < 8) {
            val cause = root.cause ?: break
            if (cause === root) break
            root = cause
            depth++
        }
        val thrower = root.stackTrace.firstOrNull { frame -> PLATFORM_PACKAGES.none { frame.className.startsWith(it) } } ?: return false
        return OWN_PACKAGES.any { thrower.className.startsWith(it) }
    }

    private val PLATFORM_PACKAGES = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
        "sun.", "jdk.", "okhttp3.", "okio.", "com.google.gson.",
    )

    private fun installCrashHandler() {
        synchronized(lock) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (enabled && isOwnCrash(error)) {
                    synchronized(lock) {
                        val p = prefs
                        // commit(), not apply(): the process is about to die.
                        if (p != null) p.edit().putInt(KEY_PREFIX + CRASH, add(p.getInt(KEY_PREFIX + CRASH, 0), 1)).commit()
                    }
                }
            } catch (_: Throwable) {
                // Never stand between the app and its crash handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun pendingLocked(): Map<Int, Int> {
        val p = prefs ?: return early.toMap()
        return p.all.mapNotNull { (key, value) ->
            val code = key.removePrefix(KEY_PREFIX).toIntOrNull()
            if (!key.startsWith(KEY_PREFIX) || code == null || value !is Int || value <= 0) null else code to value
        }.toMap()
    }

    private fun add(a: Int, b: Int): Int = (a.toLong() + b).coerceAtMost(MAX_COUNT.toLong()).toInt()

    /** Tests only. */
    internal fun resetForTests() {
        synchronized(lock) {
            prefs?.edit()?.clear()?.commit()
            prefs = null
            enabled = true
            early.clear()
        }
    }
}
