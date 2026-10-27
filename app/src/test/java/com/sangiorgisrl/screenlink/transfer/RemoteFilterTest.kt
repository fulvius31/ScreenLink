package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The search box of the remote folder listing.
 *
 * A filter is pure promise-keeping: the user types two words and expects the list
 * to answer, and every way a filter can be wrong (accents, case, a folder that
 * vanished, a locale that lowercases "I" differently) is invisible while tapping
 * through a live server. So the rules are pinned here.
 */
class RemoteFilterTest {

    private val docs = RemoteEntry(name = "Docs", path = "/srv/Docs", isDirectory = true)
    private val report = RemoteEntry(
        name = "2024 report (pdf).PDF",
        path = "/srv/2024 report (pdf).PDF",
        isDirectory = false,
        sizeBytes = 12L,
    )
    private val cafe = RemoteEntry(name = "café-menu.txt", path = "/srv/café-menu.txt", isDirectory = false)
    private val log = RemoteEntry(name = "log.txt", path = "/srv/log.txt", isDirectory = false)
    private val all = listOf(docs, report, cafe, log)

    @Test
    fun `an empty box filters nothing and copies nothing`() {
        // Same instance: the browser keeps the server's own ordering, and a list
        // rebuilt on every keystroke would make the rows jump for no reason.
        assertSame(all, RemoteFilter.filter(all, ""))
        assertSame(all, RemoteFilter.filter(all, "   "))
        assertFalse(RemoteFilter.isFiltering("  "))
    }

    @Test
    fun `a word matches anywhere in the name and keeps the listing order`() {
        assertEquals(listOf(log), RemoteFilter.filter(all, "log"))
        assertEquals(listOf(log), RemoteFilter.filter(all, "LOG"))
        // Two files end in .txt, so this is a substring answer, not a prefix one.
        assertEquals(listOf(cafe, log), RemoteFilter.filter(all, "  .txt  "))
    }

    @Test
    fun `accents are not required to type them`() {
        // The phone keyboard may not even offer the accent the server's filename has.
        assertEquals(listOf(cafe), RemoteFilter.filter(all, "cafe"))
        // ...and the other way round: the accent typed anyway still matches.
        assertEquals(listOf(cafe), RemoteFilter.filter(all, "café"))
    }

    @Test
    fun `case folding does not follow the device locale`() {
        // A Turkish device lowercases "I" to "ı"; the server's file is still IMG_001.
        val image = RemoteEntry(name = "IMG_001.JPG", path = "/srv/IMG_001.JPG", isDirectory = false)
        assertTrue(RemoteFilter.matches(image.name, "img_001"))
        assertTrue(RemoteFilter.matches(image.name, "jpg"))
    }

    @Test
    fun `several words all have to appear whatever their order`() {
        assertEquals(listOf(report), RemoteFilter.filter(all, "pdf report"))
        assertEquals(listOf(report), RemoteFilter.filter(all, "report pdf"))
        // AND, not OR: one word missing and the row goes.
        assertTrue(RemoteFilter.filter(all, "pdf zip").isEmpty())
    }

    @Test
    fun `folders are filtered by their own name`() {
        assertEquals(listOf(docs), RemoteFilter.filter(all, "docs"))
        // A folder that does not match is hidden - otherwise the box highlights
        // instead of filtering, and a 300-entry folder never narrows.
        assertEquals(listOf(log), RemoteFilter.filter(all, "log"))
    }

    @Test
    fun `a name that matches nothing empties the listing`() {
        assertTrue(RemoteFilter.filter(all, "nope").isEmpty())
        // The screen uses this to say "nothing matches", not "this folder is empty".
        assertTrue(RemoteFilter.isFiltering("nope"))
    }

    @Test
    fun `the count line knows whether anything is being filtered`() {
        assertFalse(RemoteFilter.isFiltering(""))
        assertTrue(RemoteFilter.isFiltering("a"))
        // Words that fold away to nothing (a lone combining mark) are not a filter.
        assertFalse(RemoteFilter.isFiltering("\u0301"))
    }

    @Test
    fun `an empty query matches every name so the count line stays honest`() {
        assertTrue(RemoteFilter.matches(log.name, ""))
    }
}
