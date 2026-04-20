package co.rivium.push.sdk

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Centralized thread management for Rivium Push SDK.
 *
 * This class provides shared executors to avoid creating unbounded threads
 * and potential ANR issues. All SDK background work should use these executors.
 */
object RiviumPushExecutors {

    /**
     * Single thread executor for sequential I/O operations (SharedPreferences, file I/O).
     * Using a single thread ensures writes are serialized and don't cause contention.
     */
    val ioExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RiviumPush-IO").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /**
     * Fixed thread pool for network operations.
     * Limited to 4 threads to prevent resource exhaustion.
     */
    val networkExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "RiviumPush-Network").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    /**
     * Scheduled executor for delayed/periodic tasks.
     */
    val scheduledExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "RiviumPush-Scheduled").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /**
     * Main thread handler for posting UI updates.
     * Cached to avoid creating new Handler instances.
     */
    val mainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    /**
     * Execute a task on the I/O executor (for SharedPreferences, file operations).
     */
    fun executeIO(task: Runnable) {
        ioExecutor.execute(task)
    }

    /**
     * Execute a task on the network executor.
     */
    fun executeNetwork(task: Runnable) {
        networkExecutor.execute(task)
    }

    /**
     * Execute a task on the main thread.
     */
    fun executeMain(task: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }

    /**
     * Execute a task on the main thread with a delay.
     */
    fun executeMainDelayed(delayMs: Long, task: Runnable) {
        mainHandler.postDelayed(task, delayMs)
    }

    /**
     * Schedule a task to run after a delay on a background thread.
     */
    fun scheduleBackground(delayMs: Long, task: Runnable) {
        scheduledExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Shutdown all executors. Call this when the SDK is being destroyed.
     * Note: This is typically not needed as executors should live for app lifetime.
     */
    fun shutdown() {
        ioExecutor.shutdown()
        networkExecutor.shutdown()
        scheduledExecutor.shutdown()
    }
}
