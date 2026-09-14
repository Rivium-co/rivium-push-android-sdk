package co.rivium.push.sdk.internal

/**
 * Decides whether init() should silently re-register a previously registered
 * install (OneSignal-style "on session" refresh). Pure — no Android types — so
 * the decision is unit-testable.
 */
internal object RegistrationRefreshPolicy {

    /** Re-register at most once a day when nothing else changed. */
    const val REFRESH_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    enum class Reason { STALE, FINGERPRINT_CHANGED }

    /**
     * Stable summary of everything that should trigger a refresh when it changes:
     * app version/build, SDK identity and userId.
     */
    fun fingerprint(
        appVersion: String?,
        appBuild: Long?,
        sdk: SdkIdentity,
        userId: String?
    ): String = listOf(appVersion ?: "", appBuild?.toString() ?: "", sdk.headerValue, userId ?: "")
        .joinToString("|")

    /**
     * @param hasRegisteredBefore this install completed a register() at some point
     *        and has not been unregistered since. Never refresh otherwise — the app
     *        decides when to register for the first time.
     * @param lastRegisteredAtMs timestamp of the last successful register (0 = unknown,
     *        e.g. an install upgraded from an SDK that did not record it)
     * @param storedFingerprint fingerprint saved at the last successful register, or null
     * @return the reason to refresh, or null to skip
     */
    fun decide(
        hasRegisteredBefore: Boolean,
        lastRegisteredAtMs: Long,
        nowMs: Long,
        storedFingerprint: String?,
        currentFingerprint: String,
        intervalMs: Long = REFRESH_INTERVAL_MS
    ): Reason? {
        if (!hasRegisteredBefore) return null
        if (storedFingerprint != currentFingerprint) return Reason.FINGERPRINT_CHANGED
        // Clock moved backwards (manual change) counts as stale so we self-heal.
        if (lastRegisteredAtMs <= 0 || nowMs < lastRegisteredAtMs) return Reason.STALE
        if (nowMs - lastRegisteredAtMs >= intervalMs) return Reason.STALE
        return null
    }
}
