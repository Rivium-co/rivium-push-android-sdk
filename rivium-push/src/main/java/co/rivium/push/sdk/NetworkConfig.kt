package co.rivium.push.sdk

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network configuration for API communication.
 * Provides HTTP retry logic with exponential backoff.
 */
object NetworkConfig {

    private const val TAG = "NetworkConfig"

    // MARK: - Retry Configuration

    /** Maximum number of retry attempts */
    const val MAX_RETRY_ATTEMPTS = 3

    /** Initial retry delay in milliseconds */
    const val INITIAL_RETRY_DELAY_MS = 1000L

    /** Maximum retry delay in milliseconds */
    const val MAX_RETRY_DELAY_MS = 30000L

    /** Multiplier for exponential backoff */
    const val RETRY_BACKOFF_MULTIPLIER = 2.0

    /** HTTP status codes that should trigger a retry */
    val RETRYABLE_STATUS_CODES = setOf(
        408, // Request Timeout
        429, // Too Many Requests
        500, // Internal Server Error
        502, // Bad Gateway
        503, // Service Unavailable
        504  // Gateway Timeout
    )

    // MARK: - Timeout Configuration

    /** Connect timeout in seconds */
    const val CONNECT_TIMEOUT_SECONDS = 30L

    /** Read timeout in seconds */
    const val READ_TIMEOUT_SECONDS = 30L

    /** Write timeout in seconds */
    const val WRITE_TIMEOUT_SECONDS = 30L

    // MARK: - Client Builder

    /**
     * Create an OkHttpClient with retry logic.
     */
    fun createSecureClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .build()
    }
}

/**
 * OkHttp Interceptor that implements automatic retry with exponential backoff.
 */
class RetryInterceptor : Interceptor {

    companion object {
        private const val TAG = "RetryInterceptor"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null

        for (attempt in 0..NetworkConfig.MAX_RETRY_ATTEMPTS) {
            try {
                // Close previous response if retrying
                response?.close()

                response = chain.proceed(request)

                // Success or non-retryable status code
                if (response.isSuccessful || !shouldRetry(response.code, attempt)) {
                    return response
                }

                // Close response before retry
                response.close()

                // Log and wait before retry
                val delay = calculateRetryDelay(attempt)
                Log.w(TAG, "Request failed with ${response.code}, retrying in ${delay}ms (attempt ${attempt + 1}/${NetworkConfig.MAX_RETRY_ATTEMPTS})")
                Thread.sleep(delay)

            } catch (e: IOException) {
                lastException = e

                if (!shouldRetryOnException(e, attempt)) {
                    throw e
                }

                val delay = calculateRetryDelay(attempt)
                Log.w(TAG, "Request failed with ${e.message}, retrying in ${delay}ms (attempt ${attempt + 1}/${NetworkConfig.MAX_RETRY_ATTEMPTS})")

                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Retry interrupted", ie)
                }
            }
        }

        // If we get here, all retries failed
        throw lastException ?: IOException("Request failed after ${NetworkConfig.MAX_RETRY_ATTEMPTS} retries")
    }

    private fun shouldRetry(statusCode: Int, attempt: Int): Boolean {
        if (attempt >= NetworkConfig.MAX_RETRY_ATTEMPTS) {
            return false
        }
        return NetworkConfig.RETRYABLE_STATUS_CODES.contains(statusCode)
    }

    private fun shouldRetryOnException(e: IOException, attempt: Int): Boolean {
        if (attempt >= NetworkConfig.MAX_RETRY_ATTEMPTS) {
            return false
        }

        // Retry on connection/timeout errors
        val retryableExceptions = listOf(
            "timeout",
            "connection reset",
            "connection refused",
            "no route to host",
            "network is unreachable"
        )

        val message = e.message?.lowercase() ?: return false
        return retryableExceptions.any { message.contains(it) }
    }

    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = NetworkConfig.INITIAL_RETRY_DELAY_MS *
                Math.pow(NetworkConfig.RETRY_BACKOFF_MULTIPLIER, attempt.toDouble()).toLong()
        return minOf(delay, NetworkConfig.MAX_RETRY_DELAY_MS)
    }
}
