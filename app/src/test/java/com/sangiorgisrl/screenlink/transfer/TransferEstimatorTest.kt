package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "how fast / how long left" line under a progress bar.
 *
 * The clock is injected, so the smoothing rules - the part a user actually sees
 * flicker - are checked with fake time instead of by sleeping.
 */
class TransferEstimatorTest {

    @Test
    fun `first sample seeds the rate and the remaining time`() {
        val est = TransferEstimator { 0L }
        est.reset(startMs = 0L)

        val estimate = est.update(nowMs = 1_000L, transferredBytes = 2_000L, totalBytes = 10_000L)

        assertEquals(2_000.0, estimate.bytesPerSecond, 1e-9)
        assertEquals(4.0, estimate.secondsLeft!!, 1e-9)
    }

    @Test
    fun `later samples are smoothed so the ETA cannot bounce on one burst`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)
        est.update(1_000L, 2_000L, 10_000L) // seeds 2000 B/s

        // A 1000 B/s interval: the shown rate must land between the two, weighted
        // by SMOOTHING, not jump straight to the new instant rate.
        val estimate = est.update(2_000L, 3_000L, 10_000L)
        val expected = 2_000.0 * (1 - TransferEstimator.SMOOTHING) + 1_000.0 * TransferEstimator.SMOOTHING
        assertEquals(expected, estimate.bytesPerSecond, 1e-9)
        assertTrue(estimate.bytesPerSecond in 1_000.0..2_000.0)
    }

    @Test
    fun `an unknown total never promises a time left`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)

        val estimate = est.update(1_000L, 4_000L, totalBytes = -1L)

        assertEquals(4_000.0, estimate.bytesPerSecond, 1e-9)
        assertNull(estimate.secondsLeft)
    }

    @Test
    fun `no rate yet means no time left to show`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)

        // Same instant, so no interval: nothing can be measured yet.
        val estimate = est.update(nowMs = 0L, transferredBytes = 500L, totalBytes = 10_000L)

        assertEquals(0.0, estimate.bytesPerSecond)
        assertNull(estimate.secondsLeft)
    }

    @Test
    fun `a sample that does not move forward is ignored`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)
        est.update(1_000L, 2_000L, 10_000L)

        // A replayed / out-of-order callback must not invent a negative rate.
        val estimate = est.update(2_000L, 1_000L, 10_000L)

        assertEquals(2_000.0, estimate.bytesPerSecond, 1e-9)
    }

    @Test
    fun `remaining time never goes negative once the total is passed`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)

        val estimate = est.update(1_000L, 12_000L, totalBytes = 10_000L)

        assertEquals(0.0, estimate.secondsLeft!!, 1e-9)
    }

    @Test
    fun `average rate is the honest whole-transfer figure for a finished job`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)
        est.update(1_000L, 2_000L, 8_000L)

        assertEquals(2_000.0, est.averageRate(nowMs = 4_000L, transferredBytes = 8_000L), 1e-9)
        // Nothing moved (or nothing started): no rate to claim.
        assertEquals(0.0, est.averageRate(nowMs = 4_000L, transferredBytes = 0L))
        assertEquals(0.0, est.averageRate(nowMs = 0L, transferredBytes = 5_000L))
    }

    @Test
    fun `reset forgets the old rate so a retried job starts clean`() {
        val est = TransferEstimator { 0L }
        est.reset(0L)
        est.update(1_000L, 8_000L, 10_000L) // 8000 B/s

        est.reset(startMs = 5_000L)
        val estimate = est.update(6_000L, 1_000L, 10_000L)

        assertEquals(1_000.0, estimate.bytesPerSecond, 1e-9)
        assertEquals(9.0, estimate.secondsLeft!!, 1e-9)
    }
}
