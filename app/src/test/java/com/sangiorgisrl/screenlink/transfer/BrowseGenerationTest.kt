package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule this enforces: the LAST browse the user asked for is the one the screen
 * shows, no matter what order the server answers in.
 *
 * Worth its own test because the failure is expensive and invisible in review: a
 * late listing from the previous server (or the previous folder) becomes the folder
 * the next upload writes into.
 */
class BrowseGenerationTest {

    @Test
    fun `the newest browse wins and invalidates the one before it`() {
        val gate = BrowseGeneration()

        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(first), "a stale listing may still be applied")
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `a result may be applied while nothing newer was asked for`() {
        val gate = BrowseGeneration()

        val only = gate.next()

        assertTrue(gate.isCurrent(only))
    }

    @Test
    fun `switching server drops what the old server is still sending`() {
        val gate = BrowseGeneration()
        val oldServer = gate.next()

        // selectConnection(): the in-flight listing belongs to the connection that
        // is gone.
        gate.next()

        assertFalse(gate.isCurrent(oldServer))
    }
}
