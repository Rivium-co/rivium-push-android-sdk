package co.rivium.push.sdk.internal

import co.rivium.push.sdk.BuildConfig
import co.rivium.push.sdk.Log
import co.rivium.push.sdk.RiviumPushConfig
import okhttp3.Interceptor

/**
 * Identity this SDK reports to the backend: `sdkName` / `sdkVersion` in the
 * register body and `X-Rivium-SDK: <name>/<version>` on every API request.
 *
 * Official wrappers (Flutter, React Native) set [RiviumPushConfig.wrapperSdkName]
 * and [RiviumPushConfig.wrapperSdkVersion]; when both are valid they replace the
 * native identity so the dashboard shows the SDK the app developer actually uses.
 */
internal data class SdkIdentity(val name: String, val version: String) {

    /** Value for the `X-Rivium-SDK` header. */
    val headerValue: String get() = "$name/$version"

    /** OkHttp interceptor that stamps `X-Rivium-SDK` on every request. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(HEADER, headerValue).build())
    }

    companion object {
        private const val TAG = "SdkIdentity"
        const val NATIVE_NAME = "android"
        const val HEADER = "X-Rivium-SDK"

        /** Backend contract: ≤32 chars, [A-Za-z0-9._+-]. */
        private val VALID = Regex("^[A-Za-z0-9._+-]{1,32}$")

        val native = SdkIdentity(NATIVE_NAME, BuildConfig.SDK_VERSION)

        internal fun isValid(value: String?): Boolean = value != null && VALID.matches(value)

        fun resolve(wrapperName: String?, wrapperVersion: String?): SdkIdentity {
            if (wrapperName == null && wrapperVersion == null) return native
            if (isValid(wrapperName) && isValid(wrapperVersion)) {
                return SdkIdentity(wrapperName!!, wrapperVersion!!)
            }
            Log.w(TAG, "Ignoring invalid wrapper SDK identity '$wrapperName/$wrapperVersion'")
            return native
        }

        fun from(config: RiviumPushConfig): SdkIdentity =
            resolve(config.wrapperSdkName, config.wrapperSdkVersion)
    }
}
