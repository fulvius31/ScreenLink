package com.sangiorgisrl.screenlink.transfer

/**
 * What a share intent actually carries - decided on plain strings.
 *
 * A share sheet can hand over anything, and the two things the app can do with it
 * (stage a file, or turn pasted text into a file) are decided here rather than in
 * the Activity. URIs arrive as [String] on purpose: `android.net.Uri` is a stub in
 * JVM unit tests, so the rules that decide whether the user's share lands at all
 * would otherwise be untestable - and "I shared a file and the app showed nothing"
 * is the failure this feature cannot afford.
 */
object SharePayload {

    /**
     * @param stageable URIs the queue may read (they still get copied into the app
     *   cache, because a share grant is temporary).
     * @param dropped URIs that were refused, kept so the UI can say *why* nothing
     *   appeared instead of staying silent.
     * @param text `EXTRA_TEXT`, normalised: blank becomes null.
     * @param title `EXTRA_SUBJECT`, used as the file name of a text share.
     */
    data class Decision(
        val stageable: List<String>,
        val dropped: List<String>,
        val text: String?,
        val title: String?,
    ) {
        /** True when the share carried nothing this app can turn into a file. */
        val isEmpty: Boolean get() = stageable.isEmpty() && text == null
    }

    /**
     * Decide what to do with one share intent.
     *
     * [streamUris] is EXTRA_STREAM (single or multiple), [clipUris] the clip data
     * several providers use as their only slot. Both are read and merged in order,
     * because a provider that fills both must not queue the same file twice.
     */
    fun decide(
        streamUris: List<String>,
        clipUris: List<String>,
        text: String?,
        title: String?,
    ): Decision {
        val kept = ArrayList<String>()
        val refused = ArrayList<String>()
        val seen = HashSet<String>()
        (streamUris + clipUris).forEach { raw ->
            val uri = raw.trim()
            if (uri.isEmpty() || !seen.add(uri)) return@forEach
            // No scheme means nothing can be opened from it, and a *tree* URI is a
            // shared folder: the app would need a listing plus a folder pick to go
            // anywhere with it, so it is named as dropped rather than silently lost.
            if (!uri.contains("://") || isTreeUri(uri)) refused += uri else kept += uri
        }
        return Decision(
            stageable = kept,
            dropped = refused,
            text = text?.trim()?.takeIf { it.isNotEmpty() },
            title = title?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** `content://…/tree/…` (or `…/document/…` under a tree) is a folder, not a file. */
    private fun isTreeUri(uri: String): Boolean = uri.contains("/tree/")
}
