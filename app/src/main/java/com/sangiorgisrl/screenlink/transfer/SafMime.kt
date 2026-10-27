package com.sangiorgisrl.screenlink.transfer

/**
 * The MIME types to offer a SAF provider when creating a downloaded file.
 *
 * A provider is allowed to refuse a type it does not handle - that is in
 * [android.provider.DocumentsProvider.createDocument]'s own contract ("If the MIME
 * type is not supported, the provider must throw") - and the platform's type table
 * does not know every extension this app can be asked to copy (".md" is the one that
 * broke first). Losing the whole copy over a label nobody reads would be a bad
 * trade, so the create call walks the list below and always ends with the type every
 * provider accepts.
 */
object SafMime {

    /** The type every DocumentsProvider accepts. */
    const val OCTET_STREAM: String = "application/octet-stream"

    /** Types to try, in order, always ending in [OCTET_STREAM] and never repeating. */
    fun candidates(name: String, requested: String? = null): List<String> {
        val explicit = requested?.trim()?.takeIf { it.isNotBlank() && it != "*/*" }
        val guess = explicit ?: KNOWN[extension(name)]
        return listOfNotNull(guess, OCTET_STREAM).distinct()
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").trim().lowercase()

    /**
     * Types this app is willing to name up front. A guess here only ever makes the
     * copied file look right in the user's files app; a provider that refuses one is
     * covered by the octet-stream candidate that always follows it.
     */
    private val KNOWN = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "7z" to "application/x-7z-compressed",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
