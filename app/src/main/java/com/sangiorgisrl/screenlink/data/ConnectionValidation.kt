package com.sangiorgisrl.screenlink.data

import androidx.annotation.StringRes
import com.sangiorgisrl.screenlink.R

/** Pure validation helpers for the connection form (kept injectable-free for tests). */
object ConnectionValidation {

    /**
     * Per-field messages. Material 3 wants a text field's own error state
     * (red outline + supporting text under the field), not one shared error
     * line at the bottom of the form, so validation reports per field and
     * [validate] just picks the first one for callers that want a single message.
     *
     * A message is a string RESOURCE id, never text: validation runs outside the
     * composition and outlives a language change, so it names what to say rather
     * than saying it.
     */
    data class FieldErrors(
        @get:StringRes val displayName: Int? = null,
        @get:StringRes val username: Int? = null,
        @get:StringRes val host: Int? = null,
        @get:StringRes val port: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = displayName == null && username == null && host == null && port == null

        /** First message in field order - the message a single-line error would show. */
        @get:StringRes
        val first: Int?
            get() = displayName ?: username ?: host ?: port
    }

    fun validateFields(
        displayName: String,
        username: String,
        host: String,
        port: String,
    ): FieldErrors = FieldErrors(
        displayName = if (displayName.isBlank()) R.string.err_connection_name else null,
        username = if (username.isBlank()) R.string.err_username else null,
        host = if (host.isBlank()) R.string.err_host else null,
        port = if (port.toIntOrNull() == null || port.toInt() !in 1..65535) {
            R.string.err_port
        } else null,
    )

    /** The first field error, or null when the form is valid. */
    @StringRes
    fun validate(
        displayName: String,
        username: String,
        host: String,
        port: String,
    ): Int? = validateFields(displayName, username, host, port).first

    /** Strip control characters from a screen-session name (they would break shell quoting). */
    fun sanitizeSessionName(value: String): String = value.replace(Regex("[\\u0000\\r\\n]"), "")

    /** Only digits for the port field. */
    fun sanitizePort(value: String): String = value.filter { it.isDigit() }.take(5)
}
