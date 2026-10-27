package com.sangiorgisrl.screenlink.terminal.view

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.sangiorgisrl.screenlink.terminal.emulator.TermSession

/**
 * The callback surface between [TerminalView] and whoever owns the session it shows.
 *
 * It carries configuration questions the view cannot answer on its own, plus the events
 * the view collects - hardware keys, IME input, logs. (The IME part is why this exists
 * next to [View.setOnKeyListener] rather than instead of it.) Set it through
 * [TerminalView.client].
 */
interface TerminalViewClient {

    /** A pinch-zoom step, with the factor meaning of [ScaleGestureDetector.getScaleFactor]. */
    fun onScale(scale: Float): Float

    /** A single tap on the terminal, when the program on screen is not asking for mouse reports. */
    fun onSingleTapUp(e: MotionEvent)

    fun shouldBackButtonBeMappedToEscape(): Boolean

    fun shouldEnforceCharBasedInput(): Boolean

    fun shouldUseCtrlSpaceWorkaround(): Boolean

    fun isTerminalViewSelected(): Boolean

    fun copyModeChanged(copyMode: Boolean)

    fun onKeyDown(keyCode: Int, e: KeyEvent, session: TermSession): Boolean

    fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean

    fun onLongPress(event: MotionEvent): Boolean

    fun readControlKey(): Boolean

    fun readAltKey(): Boolean

    fun readShiftKey(): Boolean

    fun readFnKey(): Boolean

    fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TermSession): Boolean

    fun onEmulatorSet()

    fun logError(tag: String, message: String)

    fun logWarn(tag: String, message: String)

    fun logInfo(tag: String, message: String)

    fun logDebug(tag: String, message: String)

    fun logVerbose(tag: String, message: String)

    fun logStackTraceWithMessage(tag: String, message: String, e: Exception)

    fun logStackTrace(tag: String, e: Exception)
}
