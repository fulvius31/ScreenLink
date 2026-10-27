package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tick-boxes of the remote browser.
 *
 * Two rules annoy users more than anything else here - a tap that does not
 * register, and a refresh that silently un-ticks what they chose - and both are
 * invisible while tapping through a live server, so they are pinned as pure
 * functions instead.
 */
class RemoteSelectionTest {

    private val file = RemoteEntry(name = "plan.pdf", path = "/srv/plan.pdf", isDirectory = false, sizeBytes = 10L)
    private val other = RemoteEntry(name = "log.txt", path = "/srv/log.txt", isDirectory = false, sizeBytes = 20L)
    private val folder = RemoteEntry(name = "docs", path = "/srv/docs", isDirectory = true)
    private val socket = RemoteEntry(
        name = "sock",
        path = "/srv/sock",
        isDirectory = false,
        isCopyable = false,
    )

    @Test
    fun `a tap ticks a file and a second tap unticks it`() {
        val ticked = RemoteSelection.toggle(emptyMap(), file)

        assertEquals(setOf("/srv/plan.pdf"), ticked.keys)
        // The ENTRY travels with the tick: the queue needs its size and name even
        // after the user navigated to another folder.
        assertEquals(10L, ticked.getValue("/srv/plan.pdf").sizeBytes)
        assertTrue(RemoteSelection.toggle(ticked, file).isEmpty())
    }

    @Test
    fun `taps keep several files in the order they were ticked`() {
        val selected = RemoteSelection.toggle(RemoteSelection.toggle(emptyMap(), other), file)

        assertEquals(listOf("/srv/log.txt", "/srv/plan.pdf"), selected.keys.toList())
    }

    @Test
    fun `a folder tap never ticks anything`() {
        // Tapping a folder opens it; a folder is never a copyable selection.
        assertTrue(RemoteSelection.toggle(emptyMap(), folder).isEmpty())
    }

    @Test
    fun `something that cannot be copied never ticks`() {
        assertTrue(RemoteSelection.toggle(emptyMap(), socket).isEmpty())
    }

    @Test
    fun `a refresh keeps the ticks and re-reads what the file is now`() {
        val selected = mapOf("/srv/plan.pdf" to file)
        val grown = file.copy(sizeBytes = 999L, modifiedAtMillis = 5_000L)

        val synced = RemoteSelection.sync(selected, listOf(grown, other))

        assertEquals(setOf("/srv/plan.pdf"), synced.keys)
        assertEquals(999L, synced.getValue("/srv/plan.pdf").sizeBytes)
    }

    @Test
    fun `a tick survives the file leaving the listing`() {
        // The user navigated somewhere else (or the folder failed to re-open): the
        // tick must survive, because losing it under their finger is worse than a
        // later "no such file" that says what actually happened.
        val selected = mapOf("/srv/plan.pdf" to file)

        val synced = RemoteSelection.sync(selected, listOf(other))

        assertEquals(setOf("/srv/plan.pdf"), synced.keys)
        assertEquals(file, synced.getValue("/srv/plan.pdf"))
    }

    @Test
    fun `a tick is dropped only when the entry stopped being copyable`() {
        val selected = mapOf("/srv/plan.pdf" to file, "/srv/log.txt" to other)
        val nowAFolder = file.copy(isDirectory = true)

        val synced = RemoteSelection.sync(selected, listOf(nowAFolder, other))

        assertFalse("/srv/plan.pdf" in synced.keys)
        assertEquals(setOf("/srv/log.txt"), synced.keys)
    }

    @Test
    fun `an empty selection stays empty through any listing`() {
        assertTrue(RemoteSelection.sync(emptyMap(), listOf(file, folder)).isEmpty())
    }
}
