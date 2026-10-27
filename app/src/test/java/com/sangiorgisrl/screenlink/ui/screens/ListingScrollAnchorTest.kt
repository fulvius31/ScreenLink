package com.sangiorgisrl.screenlink.ui.screens

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where the file listing lands when the folder or the filter under it changes.
 *
 * Every automatic scroll is a promise about where the user is looking, and a device
 * cannot be trusted to be scrolled to the same place twice - so the rules are pinned
 * against the only thing they actually read: the keys of the rows that are on screen.
 */
class ListingScrollAnchorTest {

    /** The rows of the screen, in order, exactly as the LazyColumn lays them out. */
    private val page = listOf(
        "mode",
        "connection",
        "pending",
        ListingScrollAnchor.BROWSER_CARD_KEY,
        ListingScrollAnchor.SEARCH_ROW_KEY,
        "/srv/one.txt",
        "/srv/two.txt",
    )

    private fun browserIndex(keys: List<Any>) =
        ListingScrollAnchor.indexOfKey(keys, ListingScrollAnchor.BROWSER_CARD_KEY)

    private fun searchIndex(keys: List<Any>) =
        ListingScrollAnchor.indexOfKey(keys, ListingScrollAnchor.SEARCH_ROW_KEY)

    // --- reading the screen --------------------------------------------------

    @Test
    fun `a row that is on screen is found by its key`() {
        assertEquals(3, browserIndex(page))
        assertEquals(4, searchIndex(page))
    }

    @Test
    fun `a row that is off screen has no index`() {
        // Scrolled deep into the entries: only the sticky box is still laid out.
        val deep = listOf(ListingScrollAnchor.SEARCH_ROW_KEY, "/srv/one.txt", "/srv/two.txt")
        assertNull(browserIndex(deep))
        assertEquals(0, searchIndex(deep))
        // Nothing of the browser at all (the queue below the listing).
        assertNull(browserIndex(emptyList()))
        assertNull(searchIndex(emptyList()))
    }

    @Test
    fun `a key that no row carries is not confused with a similar one`() {
        // "browser" must not match the transfer rows, whose keys are remote paths.
        assertNull(ListingScrollAnchor.indexOfKey(listOf("/srv/browser/x.txt"), "browser"))
        assertEquals(0, ListingScrollAnchor.indexOfKey(listOf("browser"), "browser"))
    }

    // --- a new folder --------------------------------------------------------

    @Test
    fun `a new folder lands on the card that names it, not on the top of the page`() {
        // Index 0 is the mode selector: scrolling there pushes the path, the
        // breadcrumbs and the first rows off the screen, which is what made a folder
        // change look like the app had lost its place.
        assertEquals(3, ListingScrollAnchor.folderAnchorIndex(browserIndex(page), searchIndex(page)))
    }

    @Test
    fun `a new folder with the card off screen still lands on the search box`() {
        val deep = listOf(ListingScrollAnchor.SEARCH_ROW_KEY, "/srv/one.txt")
        assertEquals(
            0,
            ListingScrollAnchor.folderAnchorIndex(browserIndex(deep), searchIndex(deep)),
        )
    }

    @Test
    fun `a listing that is not on screen at all falls back to the top`() {
        // Nothing to anchor on (no connection picked yet, or the listing failed):
        // the top of the page is the only honest answer.
        assertEquals(0, ListingScrollAnchor.folderAnchorIndex(null, null))
        assertEquals(
            0,
            ListingScrollAnchor.folderAnchorIndex(browserIndex(emptyList()), searchIndex(emptyList())),
        )
    }

    // --- a new filter word ---------------------------------------------------

    @Test
    fun `the rows do not move while the search box is on screen`() {
        // The box is a sticky header, so it is readable wherever the user scrolled;
        // re-anchoring it on every keystroke would slide the whole listing under
        // their fingers and hide the row they were about to tap.
        assertNull(ListingScrollAnchor.queryAnchorIndex(searchIndex(page), browserIndex(page)))
        assertNull(ListingScrollAnchor.queryAnchorIndex(searchIndex = 7, browserIndex = null))
    }

    @Test
    fun `the listing is pulled back only when the box really has gone away`() {
        // Scrolled down past the last row, into the transfer queue: the box is gone,
        // so the folder card is what drags it back.
        assertEquals(2, ListingScrollAnchor.queryAnchorIndex(searchIndex = null, browserIndex = 2))
        // And if even that is off screen, nothing is guessed: stay put.
        assertNull(ListingScrollAnchor.queryAnchorIndex(searchIndex = null, browserIndex = null))
    }
}
