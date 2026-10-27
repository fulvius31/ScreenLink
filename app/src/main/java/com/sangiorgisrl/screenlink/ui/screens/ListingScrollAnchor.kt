package com.sangiorgisrl.screenlink.ui.screens

/**
 * Where the file listing lands when something under it changes.
 *
 * A folder browser is a list the user is standing in the middle of, and every
 * automatic scroll is a promise that the app knows where the person is looking.
 * The rules are here, rather than inline in the composable, because they are the
 * kind that can only be checked by asking "what is on screen right now?" - which a
 * JVM test can answer with a list of keys, while a device cannot be trusted to be
 * scrolled to the same place twice.
 */
object ListingScrollAnchor {

    /** Lazy key of the card that names the remote folder (path + breadcrumbs). */
    const val BROWSER_CARD_KEY: String = "browser"

    /** Lazy key of the sticky search box. */
    const val SEARCH_ROW_KEY: String = "search"

    /**
     * Index of [key] among the rows that are laid out right now, or null when the
     * row is off screen.
     *
     * Keys are opaque to the lazy layout (any [Any]), hence the loose type: the
     * caller hands over [androidx.compose.foundation.lazy.LazyListLayoutInfo.visibleItemsInfo]
     * mapped to its keys.
     */
    fun indexOfKey(visibleKeys: List<Any>, key: String): Int? =
        visibleKeys.indexOfFirst { it == key }.takeIf { it >= 0 }

    /**
     * The row to land on after a NEW folder arrived.
     *
     * The card that names the folder wins: after walking into a directory the first
     * thing a person checks is *where they are* (path, breadcrumbs) and only then the
     * rows. Jumping to index 0 instead - the mode selector, the file list - pushes
     * that card off the screen and the listing appears to have opened "nowhere".
     *
     * When that card is off screen the search box is the next best anchor, and only
     * if neither is laid out does the listing fall back to its very top.
     */
    fun folderAnchorIndex(browserIndex: Int?, searchIndex: Int?): Int =
        browserIndex ?: searchIndex ?: 0

    /**
     * The row to land on after a new filter word, or null for "leave the listing
     * exactly where it is".
     *
     * Nothing moves while the box is on screen: it is a sticky header, so it is
     * already readable, and re-anchoring it on every keystroke would slide the whole
     * listing under the user's fingers. The listing is only pulled back when the box
     * really has gone off screen (scrolled down past the last row, into the queue),
     * and then to the card that names the folder, which drags the box back with it.
     */
    fun queryAnchorIndex(searchIndex: Int?, browserIndex: Int?): Int? =
        if (searchIndex != null) null else browserIndex
}
