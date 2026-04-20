package co.rivium.push.sdk

/**
 * Log levels for the Rivium Push SDK.
 * Controls verbosity of logging output.
 */
enum class RiviumPushLogLevel {
    /** No logging at all (for production) */
    NONE,
    /** Only errors */
    ERROR,
    /** Errors and warnings */
    WARNING,
    /** Errors, warnings, and info messages */
    INFO,
    /** All messages including debug output (default for development) */
    DEBUG,
    /** Everything including very detailed traces */
    VERBOSE;

    companion object {
        fun fromString(level: String): RiviumPushLogLevel {
            return when (level.lowercase()) {
                "none" -> NONE
                "error" -> ERROR
                "warning" -> WARNING
                "info" -> INFO
                "debug" -> DEBUG
                "verbose" -> VERBOSE
                else -> DEBUG
            }
        }
    }
}

/**
 * Logger for Rivium Push SDK with configurable log levels.
 */
object RiviumPushLogger {
    var logLevel: RiviumPushLogLevel = RiviumPushLogLevel.DEBUG

    fun setLogLevelFromString(level: String) {
        logLevel = RiviumPushLogLevel.fromString(level)
    }

    fun v(tag: String, message: String) {
        if (logLevel >= RiviumPushLogLevel.VERBOSE) {
            android.util.Log.v("RiviumPush.$tag", message)
        }
    }

    fun d(tag: String, message: String) {
        if (logLevel >= RiviumPushLogLevel.DEBUG) {
            android.util.Log.d("RiviumPush.$tag", message)
        }
    }

    fun i(tag: String, message: String) {
        if (logLevel >= RiviumPushLogLevel.INFO) {
            android.util.Log.i("RiviumPush.$tag", message)
        }
    }

    fun w(tag: String, message: String) {
        if (logLevel >= RiviumPushLogLevel.WARNING) {
            android.util.Log.w("RiviumPush.$tag", message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (logLevel >= RiviumPushLogLevel.ERROR) {
            if (throwable != null) {
                android.util.Log.e("RiviumPush.$tag", message, throwable)
            } else {
                android.util.Log.e("RiviumPush.$tag", message)
            }
        }
    }
}

/**
 * Convenience object for logging with automatic tag prefixing.
 * Use: Log.d(TAG, "message") instead of RiviumPushLogger.d(TAG, "message")
 */
object Log {
    fun v(tag: String, message: String) = RiviumPushLogger.v(tag, message)
    fun d(tag: String, message: String) = RiviumPushLogger.d(tag, message)
    fun i(tag: String, message: String) = RiviumPushLogger.i(tag, message)
    fun w(tag: String, message: String) = RiviumPushLogger.w(tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = RiviumPushLogger.e(tag, message, throwable)
}
