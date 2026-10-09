package co.rivium.push.sdk.internal

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 12+ refuses to start the push service while the app is not in the
 * foreground (it used to crash the app). The start is deferred, and tried
 * again only when the app really comes to the foreground.
 */
class DeferredServiceStartTest {

    @Test
    fun `refused in the background - retries when the app is opened`() {
        // On top with the screen off: the process lifecycle is stopped.
        val deferred = DeferredServiceStart(startedWhenRefused = false)

        // A new observer is replayed ON_CREATE; that is not the app opening.
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_CREATE))
        assertTrue(deferred.shouldRetryOn(Lifecycle.Event.ON_START))
    }

    @Test
    fun `refused while counted as started - waits for a real return to the foreground`() {
        val deferred = DeferredServiceStart(startedWhenRefused = true)

        // Replayed to the new observer: the state it was refused in.
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_CREATE))
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_START))
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_RESUME))

        // The app leaves and comes back.
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_PAUSE))
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_STOP))
        assertTrue(deferred.shouldRetryOn(Lifecycle.Event.ON_START))
    }

    @Test
    fun `other events never trigger a retry`() {
        val deferred = DeferredServiceStart(startedWhenRefused = false)

        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_RESUME))
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_PAUSE))
        assertFalse(deferred.shouldRetryOn(Lifecycle.Event.ON_DESTROY))
    }
}
