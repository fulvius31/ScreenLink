package com.sangiorgisrl.screenlink.transfer

/**
 * The tick-box state of the remote browser, as pure functions.
 *
 * The selection is a MAP of path -> entry rather than a set of paths, because the
 * queue needs the entry (name, size, whether it is copyable) when the user presses
 * "Copy to device". Keeping the entry means the screen never has to hope the file is
 * still in the listing it happens to be looking at - and a listing can be gone
 * (navigated away, refreshed, the folder failed to open) while the user's ticks are
 * still there.
 *
 * Pure so the two rules that annoy users most - a tap that does not register, and a
 * refresh that silently un-ticks their selection - can be pinned by tests.
 */
object RemoteSelection {

    /** Tick / untick one entry. Folders and non-copyable entries are ignored. */
    fun toggle(selected: Map<String, RemoteEntry>, entry: RemoteEntry): Map<String, RemoteEntry> {
        if (entry.isDirectory || !entry.isCopyable) return selected
        return if (entry.path in selected) selected - entry.path else selected + (entry.path to entry)
    }

    /**
     * Re-point the ticks at a fresh listing.
     *
     * Sizes and dates are refreshed from [listing] (the file may have grown, or been
     * replaced), but a tick is NEVER dropped just because the entry is not in this
     * listing: the user ticked it, and a file that really vanished on the server
     * should fail with "no such file" rather than lose its tick under their finger.
     */
    fun sync(selected: Map<String, RemoteEntry>, listing: List<RemoteEntry>): Map<String, RemoteEntry> {
        if (selected.isEmpty()) return selected
        val fresh = listing.associateBy { it.path }
        val out = LinkedHashMap<String, RemoteEntry>(selected.size)
        selected.forEach { (path, old) ->
            val now = fresh[path]
            when {
                // Not in this listing any more: keep the tick. The user chose it, and
                // a file that really vanished on the server fails with "no such file",
                // which is honest - losing its tick under their finger is not.
                now == null -> out[path] = old
                // It stopped being a file we can copy (became a folder, a socket):
                // dropping the tick is the only answer that cannot fail later on.
                now.isDirectory || !now.isCopyable -> Unit
                else -> out[path] = now
            }
        }
        return out
    }
}
