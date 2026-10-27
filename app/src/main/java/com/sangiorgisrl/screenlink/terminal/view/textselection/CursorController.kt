package com.sangiorgisrl.screenlink.terminal.view.textselection

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.view.MotionEvent
import android.view.ViewTreeObserver
import com.sangiorgisrl.screenlink.terminal.view.TerminalView

/**
 * Drives the selection cursors shown over the terminal text. Only [TerminalView] uses it.
 */
interface CursorController : ViewTreeObserver.OnTouchModeChangeListener {

    /** Put the cursors on screen; they are painted by [render] during onDraw. See [hide]. */
    fun show(event: MotionEvent)

    /** Take the cursors off screen. See [show]. */
    fun hide(): Boolean

    /** Paint the cursors. */
    fun render()

    fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int)

    /**
     * Called from the view's touch handling so the cursors can become active and/or visible.
     */
    fun onTouchEvent(event: MotionEvent): Boolean

    /**
     * Called when the view leaves its window. Housekeeping only: the pending runnables
     * would otherwise pin the context and keep the activity from being reclaimed.
     */
    fun onDetached()

    fun isActive(): Boolean
}
