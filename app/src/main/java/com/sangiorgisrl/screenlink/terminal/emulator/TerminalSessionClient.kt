package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

/**
 * Callbacks from a [TermSession] to whoever owns it: the UI layer in this app
 * (see `ui/terminal/TerminalClients.kt`), a recording stub in the tests.
 */
interface TerminalSessionClient {

    fun onTextChanged(changedSession: TermSession)

    fun onTitleChanged(changedSession: TermSession)

    fun onSessionFinished(finishedSession: TermSession)

    fun onCopyTextToClipboard(session: TermSession, text: String)

    fun onPasteTextFromClipboard(session: TermSession)

    fun onBell(session: TermSession)

    fun onColorsChanged(session: TermSession)

    fun onTerminalCursorStateChange(state: Boolean)

    /** The configured cursor style, or null to let the emulator use its own default. */
    fun getTerminalCursorStyle(): Int?

    fun logError(tag: String, message: String)

    fun logWarn(tag: String, message: String)

    fun logInfo(tag: String, message: String)

    fun logDebug(tag: String, message: String)

    fun logVerbose(tag: String, message: String)

    fun logStackTraceWithMessage(tag: String, message: String, e: Exception)

    fun logStackTrace(tag: String, e: Exception)
}
