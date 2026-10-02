package co.rivium.push.sdk.internal

import android.content.Context
import co.rivium.push.sdk.Log
import co.rivium.push.sdk.PushTokenProvider
import co.rivium.push.sdk.RiviumPush
import co.rivium.push.sdk.RiviumPushAuthError
import co.rivium.push.sdk.RiviumPushAuthErrorListener
import co.rivium.push.sdk.RiviumPushBlockingTokenProvider
import co.rivium.push.sdk.RiviumPushExecutors
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Process-wide user token state shared by every HTTP client of the SDK
 * (see [UserTokenInterceptor]). Lives outside [RiviumPush] so it also works in
 * a process started for the push service or a broadcast, before init().
 */
internal object UserTokenSession {
    private const val TAG = "UserToken"
    private const val PREFS_NAME = "rivium_push_prefs"
    private const val KEY_USER_TOKEN = "userToken"

    // Provider calls get their own threads so a slow provider never holds up SDK work.
    private val providerExecutor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "RiviumPush-Token").apply { isDaemon = true }
        }
    }

    @Volatile
    var manager: UserTokenManager = newManager()
        private set

    @Volatile
    var listener: RiviumPushAuthErrorListener? = null

    private fun newManager() = UserTokenManager(
        executor = { task -> providerExecutor.execute(task) },
        onProviderFailed = { cause ->
            report(
                RiviumPushAuthError(
                    RiviumPushAuthError.TOKEN_PROVIDER_FAILED,
                    cause.message ?: "tokenProvider failed",
                    cause
                )
            )
        }
    )

    /** Enable persistence next to the SDK's other credentials. Safe to call repeatedly. */
    fun attach(context: Context) {
        val appContext = context.applicationContext ?: context
        manager.attachStore(object : UserTokenManager.Store {
            private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

            override fun load(): String? = prefs.getString(KEY_USER_TOKEN, null)

            override fun save(token: String?) {
                RiviumPushExecutors.executeIO {
                    val editor = prefs.edit()
                    if (token == null) editor.remove(KEY_USER_TOKEN) else editor.putString(KEY_USER_TOKEN, token)
                    editor.apply()
                }
            }
        })
    }

    fun setProvider(provider: PushTokenProvider?) {
        manager.setProvider(provider?.let { suspending -> { runBlocking { suspending() } } })
    }

    fun setBlockingProvider(provider: RiviumPushBlockingTokenProvider?) {
        manager.setProvider(provider?.let { blocking -> { blocking.getToken() } })
    }

    /** Deliver an auth error to the app on the main thread. Never throws. */
    fun report(error: RiviumPushAuthError) {
        try {
            Log.w(TAG, "Auth error: ${error.code} - ${error.message}")
            RiviumPushExecutors.executeMain {
                try {
                    listener?.onAuthError(error)
                    RiviumPush.callbackOrNull()?.onAuthError(error)
                } catch (e: Exception) {
                    Log.w(TAG, "Auth error handler threw: ${e.message}")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Fresh state (used by tests). */
    internal fun reset() {
        manager = newManager()
        listener = null
    }
}
