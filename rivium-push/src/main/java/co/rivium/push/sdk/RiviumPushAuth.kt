package co.rivium.push.sdk

/**
 * Returns the Rivium user token for the signed-in user, issued by your server,
 * or null when no user is signed in.
 *
 * This is the same token the other Rivium SDKs accept, so the function you
 * already pass to Rivium Chat can be passed here unchanged. Never put the
 * server secret in the app.
 */
typealias PushTokenProvider = suspend () -> String?

/**
 * Blocking form of [PushTokenProvider] for Java callers. Called on a background
 * thread; return null when no user is signed in.
 */
fun interface RiviumPushBlockingTokenProvider {
    @Throws(Exception::class)
    fun getToken(): String?
}

/**
 * An identity error on a Rivium Push request. Informational: the request's own
 * error callback is still invoked as before.
 *
 * [code] is one of [TOKEN_INVALID], [TOKEN_REQUIRED], [TOKEN_MISMATCH],
 * [TOKEN_PROVIDER_FAILED], or [TOKEN_EXPIRED] when a refresh did not help.
 */
data class RiviumPushAuthError(
    val code: String,
    val message: String,
    val error: Throwable? = null
) {
    companion object {
        /** The server rejected the token. Send the user to login. */
        const val TOKEN_INVALID = "token_invalid"

        /** The project requires a user token and none was sent. */
        const val TOKEN_REQUIRED = "token_required"

        /** The userId given to the SDK is not the user the token was issued for. */
        const val TOKEN_MISMATCH = "token_mismatch"

        /** The token provider threw or did not answer in time. The request was sent without a token. */
        const val TOKEN_PROVIDER_FAILED = "token_provider_failed"

        /** The token is expired and no fresh one could be obtained. */
        const val TOKEN_EXPIRED = "token_expired"
    }
}

/** Receives [RiviumPushAuthError]s on the main thread. See [RiviumPush.setAuthErrorListener]. */
fun interface RiviumPushAuthErrorListener {
    fun onAuthError(error: RiviumPushAuthError)
}
