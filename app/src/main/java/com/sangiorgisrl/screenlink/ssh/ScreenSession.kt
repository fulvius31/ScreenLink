package com.sangiorgisrl.screenlink.ssh

/** A single GNU Screen session listed by `screen -ls`. */
data class ScreenSession(
    /** The full socket id, e.g. `1234.work` or `1234.pts-0.myserver`. Used by screen -dr / -X. */
    val socket: String,
    /** Human-friendly name guessed from the socket (name part when created with -S). */
    val name: String,
    /** Attached | Detached | Unavailable. */
    val state: String,
)
