package com.sangiorgisrl.screenlink.transfer

/**
 * Speed + "time left" for one running transfer.
 *
 * A raw `bytes / elapsed` average is too jumpy to show (SSH window updates arrive
 * in bursts, so the ETA visibly bounces), and an instant rate over one tiny buffer
 * is worse still. This keeps an exponentially weighted rate over the *recent*
 * samples - responsive enough to react to a real slowdown, smooth enough not to
 * flicker - and refuses to promise a time left until there is both a known total
 * and a non-zero rate.
 *
 * Pure and clock-injected: the smoothing rules are unit tested with fake time.
 */
class TransferEstimator(private val clock: () -> Long = System::currentTimeMillis) {

    private var startMs = 0L
    private var lastMs = 0L
    private var lastBytes = 0L
    private var rate = 0.0

    fun reset(startMs: Long = clock()) {
        this.startMs = startMs
        lastMs = startMs
        lastBytes = 0L
        rate = 0.0
    }

    /** Feed the cumulative byte count; returns the estimate to display now. */
    fun update(nowMs: Long, transferredBytes: Long, totalBytes: Long): TransferEstimate {
        val intervalMs = nowMs - lastMs
        if (intervalMs > 0 && transferredBytes >= lastBytes) {
            val instant = (transferredBytes - lastBytes) * 1000.0 / intervalMs
            // First real sample seeds the rate; later ones are smoothed.
            rate = if (rate <= 0.0) instant else rate * (1 - SMOOTHING) + instant * SMOOTHING
            lastMs = nowMs
            lastBytes = transferredBytes
        }
        val secondsLeft = if (totalBytes > 0L && rate > 0.0) {
            val remaining = (totalBytes - transferredBytes).coerceAtLeast(0L)
            remaining / rate
        } else {
            null
        }
        return TransferEstimate(bytesPerSecond = rate, secondsLeft = secondsLeft)
    }

    /** Average rate over the whole transfer - honest for a finished job. */
    fun averageRate(nowMs: Long, transferredBytes: Long): Double {
        val elapsed = nowMs - startMs
        return if (elapsed > 0 && transferredBytes > 0) transferredBytes * 1000.0 / elapsed else 0.0
    }

    companion object {
        /** Weight of the newest sample (0..1). Small = calmer, lazier. */
        const val SMOOTHING = 0.25
    }
}
