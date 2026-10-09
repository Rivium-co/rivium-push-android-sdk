package co.rivium.push.sdk.internal

import androidx.lifecycle.Lifecycle

/**
 * Decides when a refused service start may be tried again.
 *
 * Android 12+ refuses to start a foreground service while the app is not in
 * the foreground - for example when it is on top but the screen is off. The
 * start is then deferred until the app really comes to the foreground.
 *
 * A lifecycle observer is first replayed the events up to the current state.
 * If the app already counted as started when the start was refused, that
 * replayed ON_START is not a new foreground entry and trying again would only
 * be refused again, so the app has to stop and start first.
 */
internal class DeferredServiceStart(startedWhenRefused: Boolean) {

    private var armed = !startedWhenRefused

    /** True when [event] means the app has just come to the foreground. */
    fun shouldRetryOn(event: Lifecycle.Event): Boolean {
        return when (event) {
            Lifecycle.Event.ON_STOP -> {
                armed = true
                false
            }
            Lifecycle.Event.ON_START -> armed
            else -> false
        }
    }
}
