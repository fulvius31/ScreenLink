package com.sangiorgisrl.screenlink.transfer

/**
 * The copy queue read from OUTSIDE the queue: what still moves, what needs
 * attention, and in which order the rows are shown.
 *
 * [TransferStore] publishes its jobs in the order they were enqueued, which is
 * right for a store and wrong for a person: in a batch of forty files the row that
 * is ACTUALLY copying only reaches the top by accident, and a copy that failed
 * three screens ago scrolls out of sight under the finished ones. Both rules live
 * here as pure functions, because "which row sits where" is exactly the promise a
 * device cannot be trusted to be scrolled to the same place twice.
 */
object TransferQueueView {

    /** Counts for one glance at the queue. */
    data class Summary(
        val running: Int,
        val queued: Int,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
    ) {
        /** Work the queue still owns (the only thing "Stop all" can stop). */
        val active: Int get() = running + queued

        /** Rows that will never move again (the only thing "Clear" can clear). */
        val finished: Int get() = completed + failed + cancelled

        val isEmpty: Boolean get() = active + finished == 0

        val canStopAll: Boolean get() = active > 0

        val canClearFinished: Boolean get() = finished > 0

        companion object {
            fun of(jobs: List<TransferStore.Job>): Summary = Summary(
                running = jobs.count { it.status == TransferStatus.Running },
                queued = jobs.count { it.status == TransferStatus.Queued },
                completed = jobs.count { it.status == TransferStatus.Completed },
                failed = jobs.count { it.status == TransferStatus.Failed },
                cancelled = jobs.count { it.status == TransferStatus.Cancelled },
            )
        }
    }

    /** Whether the badge counts work in flight, or work that went wrong. */
    enum class BadgeKind { None, Active, Attention }

    data class Badge(val count: Int, val kind: BadgeKind)

    /** Highest number the badge draws literally; above it the label is a floor. */
    const val CAP = 99

    /**
     * The badge on the entry point that opens the queue from another screen.
     *
     * Work in flight wins. With nothing running, a FAILED copy is the number that
     * has to be visible from the other screen, or the user walks away believing the
     * batch went through. An all-completed queue shows NO badge: a number that never
     * clears becomes wallpaper, and the next real alert is ignored.
     */
    fun badge(summary: Summary): Badge = when {
        summary.active > 0 -> Badge(summary.active, BadgeKind.Active)
        summary.failed > 0 -> Badge(summary.failed, BadgeKind.Attention)
        else -> Badge(0, BadgeKind.None)
    }

    /**
     * What the badge says, or null when there is nothing to show.
     *
     * A three-digit count would push the icon off the top bar, so the label is a
     * floor ("99+") rather than a lie - the screen behind it shows every row.
     */
    fun badgeLabel(badge: Badge): String? = when {
        badge.kind == BadgeKind.None || badge.count <= 0 -> null
        badge.count > CAP -> "$CAP+"
        else -> badge.count.toString()
    }

    /** The two bands the queue screen shows, each in its own order. */
    data class Sections(
        val active: List<TransferStore.Job>,
        val finished: List<TransferStore.Job>,
    )

    /**
     * The row that is copying comes first (it is the reason the screen was opened),
     * then the ones waiting behind it in the order they were queued, then the
     * finished rows newest-first: five files that landed ten seconds ago must not be
     * buried under the ones that landed an hour ago.
     *
     * Both sorts are stable, so "in the order they were queued" is the store's own
     * order rather than a coincidence of the filter.
     */
    fun sections(jobs: List<TransferStore.Job>): Sections = Sections(
        active = jobs
            .filter { it.status == TransferStatus.Running || it.status == TransferStatus.Queued }
            .sortedBy { if (it.status == TransferStatus.Running) 0 else 1 },
        finished = jobs
            .filter { it.status != TransferStatus.Running && it.status != TransferStatus.Queued }
            .sortedByDescending { it.finishedAt },
    )
}
