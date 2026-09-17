package co.rivium.push.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import co.rivium.push.sdk.Log
import java.security.MessageDigest

/**
 * Stable per-install fingerprint sent as `installId` on `/devices/register`.
 *
 * The locally stored device ID is regenerated on reinstall, clear-data or a
 * fresh build, which leaves the previous device row active on the server
 * forever — one phone ends up as several "active" devices and delivery numbers
 * are inflated. `installId` is stable across those events, so the backend can
 * retire the previous row for the same physical device.
 *
 * It is derived from `Settings.Secure.ANDROID_ID`, which is scoped to the app
 * signing key, device and device user: it survives a reinstall and resets on a
 * factory reset — exactly the lifetime we want.
 *
 * The raw ANDROID_ID is a hardware-scoped identifier and is NEVER sent: only a
 * SHA-256 of `<ANDROID_ID>:<packageName>` truncated to 32 hex chars leaves the
 * device. Mixing in the package name also keeps the value from correlating the
 * same device across unrelated apps.
 */
internal object InstallId {

    private const val TAG = "InstallId"

    /** Backend contract: lowercase hex, max 64 chars. We send 32. */
    private const val LENGTH = 32

    @Volatile
    private var cached: String? = null

    /**
     * SHA-256 of `androidId + ":" + packageName`, hex, truncated to 32 chars.
     * Returns null when ANDROID_ID is missing or blank (some emulators), in
     * which case the field is omitted from the register body entirely.
     */
    fun compute(androidId: String?, packageName: String): String? {
        val id = androidId?.trim()
        if (id.isNullOrEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$id:$packageName".toByteArray(Charsets.UTF_8))

        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.substring(0, LENGTH)
    }

    /** Cached per process; ANDROID_ID cannot change while the app runs. */
    @SuppressLint("HardwareIds")
    fun get(context: Context): String? {
        cached?.let { return it }
        val value = try {
            compute(
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
                context.packageName
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not compute installId: ${e.message}")
            null
        }
        cached = value
        return value
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
