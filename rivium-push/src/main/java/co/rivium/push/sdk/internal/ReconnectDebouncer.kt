package co.rivium.push.sdk.internal

import android.os.SystemClock

/**
 * Lets one reconnect trigger through per [windowMs]; triggers arriving within
 * the window (screen on + user present + foreground often fire together) are
 * dropped. PNSocket additionally never runs two connect attempts at once.
 */
internal class ReconnectDebouncer(
    private val windowMs: Long,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastFiredAt = 0L
    private var fired = false

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (fired && now - lastFiredAt < windowMs) return false
        fired = true
        lastFiredAt = now
        return true
    }
}
