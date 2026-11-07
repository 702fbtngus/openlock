package dev.abdullah.openlock

/** Only poll() runs on the sampler; the main thread acknowledges its own probe.
 * Times are uptime, so time spent in deep sleep is not counted as a stall. */
internal class GuardHeartbeat(
    private val thresholdMs: Long = 250L,
    private val repeatMs: Long = 500L,
) {
    class Probe(val id: Long, val postedAt: Long) {
        @Volatile var acknowledgedAt: Long? = null
            private set

        fun acknowledge(now: Long) { acknowledgedAt = now }
    }

    data class Decision(
        val post: Probe? = null,
        val sample: Probe? = null,
        val recovered: Probe? = null,
    )

    private var sequence = 0L
    private var pending: Probe? = null
    private var lastSampleAt: Long? = null

    fun poll(now: Long): Decision {
        val probe = pending
        val acknowledgedAt = probe?.acknowledgedAt
        if (probe == null || acknowledgedAt != null) {
            val next = Probe(++sequence, now)
            pending = next
            lastSampleAt = null
            return Decision(post = next, recovered = probe?.takeIf {
                acknowledgedAt != null && acknowledgedAt - it.postedAt >= thresholdMs
            })
        }
        val lastSample = lastSampleAt
        if (now - probe.postedAt >= thresholdMs &&
            (lastSample == null || now - lastSample >= repeatMs)) {
            lastSampleAt = now
            return Decision(sample = probe)
        }
        return Decision()
    }
}
