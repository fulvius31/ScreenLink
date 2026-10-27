package com.sangiorgisrl.screenlink.transfer

import java.text.Normalizer
import java.util.Locale

/**
 * The rules behind the search box of the remote folder listing.
 *
 * A search box is entirely made of rules the user cannot see from the outside, and
 * every one of them has burned someone at least once, so they live here as pure
 * functions instead of inside a composable:
 *
 *  - **every word must appear** (AND, not OR): "pdf report" narrows to names
 *    holding both, which is what a person hunting for one file expects;
 *  - **case and accents are ignored**: the keyboard in the user's hand may not
 *    even offer the accent the server's filename carries, and SFTP listings come
 *    from servers whose naming has nothing to do with the phone's language;
 *  - **folders are filtered by their own name**: a folder that does not match is
 *    hidden, because a listing that keeps every folder is a highlighter, not a
 *    filter - and clearing the box is one tap away when the user did want to walk
 *    there;
 *  - **an empty/blank query never copies the list**: it is returned as-is, so the
 *    browser keeps the server's own ordering whenever nothing is being filtered.
 */
object RemoteFilter {

    /** The entries whose name answers [query] (in the incoming order). */
    fun filter(entries: List<RemoteEntry>, query: String): List<RemoteEntry> {
        val words = words(query)
        if (words.isEmpty()) return entries
        return entries.filter { entry ->
            val name = normalize(entry.name)
            words.all { name.contains(it) }
        }
    }

    /** True when [name] answers [query] (same rules as [filter]). */
    fun matches(name: String, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = normalize(name)
        return words.all { haystack.contains(it) }
    }

    /** True when something is actually being filtered (drives the "N of M" line). */
    fun isFiltering(query: String): Boolean = words(query).isNotEmpty()

    private fun words(query: String): List<String> =
        query.split(WORD_SEPARATOR)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * Fold a name for comparison: strip the accent marks (combining chars left by
     * NFD) and lowercase with a fixed locale, so a Turkish or Azerbaijani device
     * does not turn "I" into "ı" and stop matching the server's "IMG_001.JPG".
     */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    private val WORD_SEPARATOR = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{M}+")
}
