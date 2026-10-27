package com.sangiorgisrl.screenlink.terminal.emulator

import java.nio.charset.StandardCharsets

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

/**
 * The byte sink and event source of a [TerminalEmulator].
 *
 * Everything the emulator wants to put back on the transport - the reply to a device
 * attributes query, a bracketed-paste echo, a title change, a bell - arrives here.
 */
abstract class TerminalOutput {

    /** Write [data] to the transport using the UTF-8 encoding. */
    fun write(data: String?) {
        if (data == null) return
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        write(bytes, 0, bytes.size)
    }

    abstract fun write(data: ByteArray, offset: Int, count: Int)

    abstract fun titleChanged(oldTitle: String?, newTitle: String?)

    abstract fun onCopyTextToClipboard(text: String)

    abstract fun onPasteTextFromClipboard()

    /** A bell character (ASCII 7) was received. */
    abstract fun onBell()

    abstract fun onColorsChanged()
}
