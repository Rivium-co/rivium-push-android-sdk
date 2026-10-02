package co.rivium.push.sdk.internal

import co.rivium.push.sdk.RiviumPushAuthError
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * The one place that puts the signed user token on Rivium Push requests.
 *
 * - Adds `x-user-token` when a token is available; otherwise the request goes
 *   out exactly as before.
 * - On `401 token_expired` fetches a fresh token and retries once.
 * - Reports other identity errors (`token_invalid`, `token_required`, userId
 *   mismatch) to the app; the response is returned to the caller untouched.
 *
 * OkHttp runs interceptors on its own or the caller's background thread, so
 * waiting for the token provider here never blocks the main thread.
 */
internal class UserTokenInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val tokens = UserTokenSession.manager
        val token = tokens.get()

        val response = chain.proceed(withToken(original, token))
        val failure = authFailureOf(response) ?: return response

        if (failure.code == RiviumPushAuthError.TOKEN_EXPIRED && token != null) {
            val fresh = tokens.refresh(rejected = token)
            if (fresh != null) {
                response.close()
                val retried = chain.proceed(withToken(original, fresh))
                authFailureOf(retried)?.let { UserTokenSession.report(it) }
                return retried
            }
        }
        UserTokenSession.report(failure)
        return response
    }

    private fun withToken(request: Request, token: String?): Request =
        if (token == null) request else request.newBuilder().header(HEADER, token).build()

    companion object {
        const val HEADER = "x-user-token"
        private const val PEEK_BYTES = 16L * 1024L
        private val TOKEN_CODES = setOf(
            RiviumPushAuthError.TOKEN_EXPIRED,
            RiviumPushAuthError.TOKEN_INVALID,
            RiviumPushAuthError.TOKEN_REQUIRED
        )

        /** The identity error a response carries, or null. Leaves the body readable. Never throws. */
        internal fun authFailureOf(response: Response): RiviumPushAuthError? {
            if (response.code != 401 && response.code != 403) return null
            return try {
                val json = JSONObject(response.peekBody(PEEK_BYTES).string())
                val message = json.opt("message")?.toString().orEmpty()
                if (response.code == 401) {
                    val code = json.optString("code", "")
                    if (code in TOKEN_CODES) RiviumPushAuthError(code, message.ifEmpty { code }) else null
                } else if (message.contains("does not match the user token", ignoreCase = true)) {
                    RiviumPushAuthError(RiviumPushAuthError.TOKEN_MISMATCH, message)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
