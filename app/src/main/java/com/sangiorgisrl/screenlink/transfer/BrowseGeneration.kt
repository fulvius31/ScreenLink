package com.sangiorgisrl.screenlink.transfer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Which browse answer still counts.
 *
 * Tapping two folders quickly - or switching server while a listing is still in
 * flight - lets the answers arrive in the wrong order. Applying the late one is
 * not just a flicker: the screen would show a folder the user never opened, and in
 * upload mode "send" would write into that path on the wrong server.
 *
 * So every browse takes a ticket with [next], and a result is applied only while
 * its own ticket is still [isCurrent].
 */
class BrowseGeneration {

    private val generation = AtomicInteger(0)

    /** Start a browse; returns the ticket its result must carry. */
    fun next(): Int = generation.incrementAndGet()

    /** True while [ticket] is still the newest browse. */
    fun isCurrent(ticket: Int): Boolean = generation.get() == ticket
}
