package co.rivium.push.sdk.internal

/**
 * Bounded, insertion-ordered record of message ids whose delivery ack is in
 * flight or already confirmed, so a PN Protocol redelivery (QoS 1) does not
 * POST the same receipt again. Pure — persistence is left to the caller via
 * [serialize] / [restore].
 */
internal class DeliveryAckTracker(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 200
        private const val SEPARATOR = "\n"
    }

    private val acked = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > capacity
    }
    private val inFlight = HashSet<String>()

    /** Returns true if the caller should send the ack for [messageId]. */
    @Synchronized
    fun tryClaim(messageId: String): Boolean {
        if (messageId.isEmpty() || acked.containsKey(messageId) || messageId in inFlight) return false
        inFlight.add(messageId)
        return true
    }

    /** The ack was accepted by the server; remember it. */
    @Synchronized
    fun markAcked(messageId: String) {
        inFlight.remove(messageId)
        acked[messageId] = Unit
    }

    /** The ack gave up; allow a later redelivery to try again. */
    @Synchronized
    fun release(messageId: String) {
        inFlight.remove(messageId)
    }

    @Synchronized
    fun contains(messageId: String): Boolean = acked.containsKey(messageId)

    @Synchronized
    fun size(): Int = acked.size

    @Synchronized
    fun serialize(): String = acked.keys.joinToString(SEPARATOR)

    @Synchronized
    fun restore(serialized: String?) {
        if (serialized.isNullOrEmpty()) return
        serialized.split(SEPARATOR).filter { it.isNotEmpty() }.forEach { acked[it] = Unit }
    }
}
