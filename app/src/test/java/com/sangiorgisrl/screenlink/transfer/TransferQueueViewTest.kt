package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The queue read from OUTSIDE the queue: the counts on the header and on the badge,
 * and the order the rows are shown in.
 *
 * These are the promises the user actually judges the app by, and none of them can
 * be checked by tapping twice in the same way: in a batch of forty files the row
 * that is really copying has to be the FIRST one, a copy that failed must still be
 * visible from the screen that queued it, and a number that never clears becomes
 * wallpaper. So the rules are pinned here, on the JVM, against the same numbers the
 * screen renders.
 */
class TransferQueueViewTest {

    private fun job(
        id: String,
        status: TransferStatus,
        finishedAt: Long = 0L,
    ): TransferStore.Job = TransferStore.Job(
        id = id,
        groupId = "group-$id",
        direction = TransferDirection.Upload,
        connectionId = 1L,
        connectionName = "srv",
        fileName = "$id.txt",
        desiredName = "$id.txt",
        remotePath = "/srv/$id.txt",
        localUri = "content://$id",
        localDirUri = null,
        totalBytes = 10L,
        transferredBytes = if (status == TransferStatus.Completed) 10L else 0L,
        status = status,
        finishedAt = finishedAt,
    )

    // --- the counts ----------------------------------------------------------

    @Test
    fun `every row is counted exactly once`() {
        val jobs = listOf(
            job("a", TransferStatus.Running),
            job("b", TransferStatus.Running),
            job("c", TransferStatus.Queued),
            job("d", TransferStatus.Completed),
            job("e", TransferStatus.Failed),
            job("f", TransferStatus.Cancelled),
        )
        val summary = TransferQueueView.Summary.of(jobs)
        assertEquals(2, summary.running)
        assertEquals(1, summary.queued)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.cancelled)
        assertEquals(3, summary.active)
        assertEquals(3, summary.finished)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `an empty queue is empty and offers nothing`() {
        val summary = TransferQueueView.Summary.of(emptyList())
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.active)
        assertEquals(0, summary.finished)
        assertFalse(summary.canStopAll)
        assertFalse(summary.canClearFinished)
    }

    @Test
    fun `the two header actions appear only when they can do something`() {
        // "Stop all" over an idle queue and "Clear" over an empty one are buttons
        // that lie, and a lying button gets tapped twice.
        val running = TransferQueueView.Summary.of(listOf(job("a", TransferStatus.Running)))
        assertTrue(running.canStopAll)
        assertFalse(running.canClearFinished)

        val settled = TransferQueueView.Summary.of(listOf(job("a", TransferStatus.Completed)))
        assertFalse(settled.canStopAll)
        assertTrue(settled.canClearFinished)
    }

    // --- the badge on the door ----------------------------------------------

    @Test
    fun `work in flight is what the badge counts`() {
        val summary = TransferQueueView.Summary.of(
            listOf(
                job("a", TransferStatus.Running),
                job("b", TransferStatus.Queued),
                job("c", TransferStatus.Failed),
                job("d", TransferStatus.Completed),
            ),
        )
        val badge = TransferQueueView.badge(summary)
        assertEquals(2, badge.count)
        assertEquals(TransferQueueView.BadgeKind.Active, badge.kind)
    }

    @Test
    fun `a failed copy still shows once nothing is moving`() {
        // The user is looking at the Files screen, the batch is over, and one file
        // did not land. No badge here means they walk away believing it worked.
        val summary = TransferQueueView.Summary.of(
            listOf(job("a", TransferStatus.Completed), job("b", TransferStatus.Failed)),
        )
        val badge = TransferQueueView.badge(summary)
        assertEquals(1, badge.count)
        assertEquals(TransferQueueView.BadgeKind.Attention, badge.kind)
    }

    @Test
    fun `a queue that only succeeded shows no badge`() {
        // A number that never clears becomes wallpaper, and the next real alert is
        // then ignored. "Done" is not something to keep pointing at.
        val summary = TransferQueueView.Summary.of(
            listOf(job("a", TransferStatus.Completed), job("b", TransferStatus.Cancelled)),
        )
        assertEquals(TransferQueueView.BadgeKind.None, TransferQueueView.badge(summary).kind)
        assertEquals(0, TransferQueueView.badge(summary).count)
    }

    @Test
    fun `an empty queue has no badge`() {
        assertEquals(TransferQueueView.BadgeKind.None, TransferQueueView.badge(SUMMARY_EMPTY).kind)
    }

    // --- what the badge says -------------------------------------------------

    @Test
    fun `no badge means no label, not a zero`() {
        // A "0" pinned to the icon is noise the user has to decode, and it is the
        // kind of number that stops being believed.
        assertNull(TransferQueueView.badgeLabel(TransferQueueView.Badge(0, TransferQueueView.BadgeKind.None)))
        assertNull(
            TransferQueueView.badgeLabel(TransferQueueView.Badge(3, TransferQueueView.BadgeKind.None)),
        )
    }

    @Test
    fun `a real count is printed as itself, and a huge one as a floor`() {
        assertEquals(
            "1",
            TransferQueueView.badgeLabel(TransferQueueView.Badge(1, TransferQueueView.BadgeKind.Active)),
        )
        assertEquals(
            "99",
            TransferQueueView.badgeLabel(
                TransferQueueView.Badge(TransferQueueView.CAP, TransferQueueView.BadgeKind.Active),
            ),
        )
        // Three digits would push the icon off the top bar. "99+" is honest about
        // being a floor: the screen behind the badge lists every row.
        assertEquals(
            "99+",
            TransferQueueView.badgeLabel(
                TransferQueueView.Badge(1_247, TransferQueueView.BadgeKind.Attention),
            ),
        )
    }

    // --- the order of the rows ----------------------------------------------

    @Test
    fun `the copy that is running comes before the ones waiting`() {
        // The store publishes in enqueue order, so the queued file that was staged
        // FIRST sits above the one that is actually moving - which reads as the
        // queue being stuck.
        val jobs = listOf(
            job("waiting-1", TransferStatus.Queued),
            job("waiting-2", TransferStatus.Queued),
            job("moving", TransferStatus.Running),
        )
        assertEquals(
            listOf("moving", "waiting-1", "waiting-2"),
            TransferQueueView.sections(jobs).active.map { it.id },
        )
    }

    @Test
    fun `queued rows keep the order they were staged in`() {
        val jobs = listOf(
            job("first", TransferStatus.Queued),
            job("second", TransferStatus.Queued),
            job("third", TransferStatus.Queued),
        )
        assertEquals(
            listOf("first", "second", "third"),
            TransferQueueView.sections(jobs).active.map { it.id },
        )
    }

    @Test
    fun `finished rows are newest first`() {
        // Five files that landed ten seconds ago must not be buried under the ones
        // that landed an hour ago: the question is "did the batch I just started
        // finish?", asked from the bottom of the list.
        val jobs = listOf(
            job("oldest", TransferStatus.Completed, finishedAt = 1_000L),
            job("newest", TransferStatus.Completed, finishedAt = 3_000L),
            job("middle", TransferStatus.Failed, finishedAt = 2_000L),
        )
        assertEquals(
            listOf("newest", "middle", "oldest"),
            TransferQueueView.sections(jobs).finished.map { it.id },
        )
    }

    @Test
    fun `active and finished are two halves of the same list`() {
        val jobs = listOf(
            job("a", TransferStatus.Running),
            job("b", TransferStatus.Completed, finishedAt = 5L),
            job("c", TransferStatus.Queued),
            job("d", TransferStatus.Cancelled, finishedAt = 7L),
        )
        val sections = TransferQueueView.sections(jobs)
        assertEquals(listOf("a", "c"), sections.active.map { it.id })
        assertEquals(listOf("d", "b"), sections.finished.map { it.id })
        assertEquals(jobs.size, sections.active.size + sections.finished.size)
    }

    @Test
    fun `an empty queue has no rows in either band`() {
        val sections = TransferQueueView.sections(emptyList())
        assertTrue(sections.active.isEmpty())
        assertTrue(sections.finished.isEmpty())
    }

    @Test
    fun `rows without a finish time keep the order the store published`() {
        // A settled row whose finishedAt never got stamped (an older row, a store
        // that was rebuilt) must not be shuffled at random: the sort is stable, so
        // the store's own order survives.
        val jobs = listOf(
            job("one", TransferStatus.Completed),
            job("two", TransferStatus.Failed),
            job("three", TransferStatus.Completed),
        )
        assertEquals(
            listOf("one", "two", "three"),
            TransferQueueView.sections(jobs).finished.map { it.id },
        )
    }

    private companion object {
        val SUMMARY_EMPTY = TransferQueueView.Summary.of(emptyList())
    }
}
