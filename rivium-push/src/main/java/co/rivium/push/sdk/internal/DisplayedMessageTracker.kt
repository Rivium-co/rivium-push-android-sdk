package co.rivium.push.sdk.internal

/**
 * Bounded, insertion-ordered record of message ids that were already handled
 * (shown, broadcast, passed to the callback). The same message can reach the
 * device twice — over PN Protocol and over the optional FCM add-on, or as a
 * PN Protocol redelivery — and only the first copy may be processed. Pure —
 * persistence is left to the caller via [serialize] / [restore].
 */
internal class DisplayedMessageTracker(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 500
        private const val SEPARATOR = "\n"
    }

    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > capacity
    }

    /**
     * Returns true the first time [messageId] is seen, false for a duplicate.
     * A null or empty id is never deduplicated (older backends send none).
     */
    @Synchronized
    fun tryClaim(messageId: String?): Boolean {
        if (messageId.isNullOrEmpty()) return true
        if (seen.containsKey(messageId)) return false
        seen[messageId] = Unit
        return true
    }

    @Synchronized
    fun contains(messageId: String): Boolean = seen.containsKey(messageId)

    @Synchronized
    fun size(): Int = seen.size

    @Synchronized
    fun serialize(): String = seen.keys.joinToString(SEPARATOR)

    @Synchronized
    fun restore(serialized: String?) {
        if (serialized.isNullOrEmpty()) return
        serialized.split(SEPARATOR).filter { it.isNotEmpty() }.forEach { seen[it] = Unit }
    }
}
