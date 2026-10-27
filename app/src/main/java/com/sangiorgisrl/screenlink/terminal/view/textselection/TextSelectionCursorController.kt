package com.sangiorgisrl.screenlink.terminal.view.textselection

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.terminal.emulator.TerminalBuffer
import com.sangiorgisrl.screenlink.terminal.emulator.WcWidth
import com.sangiorgisrl.screenlink.terminal.view.TerminalView
import kotlin.math.roundToInt

/** The two end handles plus the floating selection toolbar for one terminal selection. */
class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {

    private companion object {
        const val ACTION_COPY = 1
        const val ACTION_PASTE = 2
        const val ACTION_MORE = 3

        /**
         * Hiding right after showing is a misfire (long-pressing the down key does it);
         * 300ms is long enough that tapping a toolbar button still works.
         */
        const val HIDE_AFTER_SHOW_GRACE_MS = 300L
    }

    private val startHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
    private val endHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)

    private var isSelectingText = false
    private var showStartTime = System.currentTimeMillis()

    private val handleHeight = maxOf(startHandle.handleHeight, endHandle.handleHeight)

    private var selX1 = -1
    private var selX2 = -1
    private var selY1 = -1
    private var selY2 = -1

    private var actionMode: ActionMode? = null

    override fun show(event: MotionEvent) {
        setInitialTextSelectionPosition(event)
        startHandle.positionAtCursor(selX1, selY1, true)
        endHandle.positionAtCursor(selX2 + 1, selY2, true)

        setActionModeCallBacks()
        showStartTime = System.currentTimeMillis()
        isSelectingText = true
    }

    override fun hide(): Boolean {
        if (!isActive()) return false

        if (System.currentTimeMillis() - showStartTime < HIDE_AFTER_SHOW_GRACE_MS) {
            return false
        }

        startHandle.hide()
        endHandle.hide()

        actionMode?.let {
            // Finishing the mode comes back around and hides this controller.
            it.finish()
        }

        selX1 = -1
        selY1 = -1
        selX2 = -1
        selY2 = -1
        isSelectingText = false

        return true
    }

    override fun render() {
        if (!isActive()) return

        startHandle.positionAtCursor(selX1, selY1, false)
        endHandle.positionAtCursor(selX2 + 1, selY2, false)

        actionMode?.invalidate()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) {
        val columnAndRow = terminalView.getColumnAndRow(event, true)
        selX1 = columnAndRow[0]
        selX2 = columnAndRow[0]
        selY1 = columnAndRow[1]
        selY2 = columnAndRow[1]

        val screen = terminalView.emulator!!.screen
        if (screen.getSelectedText(selX1, selY1, selX1, selY1) != " ") {
            // Tapped a non-blank cell: grow the selection out to the whole word.
            while (selX1 > 0 && screen.getSelectedText(selX1 - 1, selY1, selX1 - 1, selY1) != "") {
                selX1--
            }
            while (selX2 < terminalView.emulator!!.columns - 1 &&
                screen.getSelectedText(selX2 + 1, selY1, selX2 + 1, selY1) != ""
            ) {
                selX2++
            }
        }
    }

    fun setActionModeCallBacks() {
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT

                val clipboard = terminalView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.terminal_action_copy).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.terminal_action_paste)
                    .setEnabled(clipboard?.hasPrimaryClip() ?: false)
                    .setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.terminal_action_more)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (!isActive()) {
                    // The toolbar can be tapped while it is going away.
                    return true
                }

                when (item.itemId) {
                    ACTION_COPY -> {
                        val selectedText = terminalView.emulator!!.getSelectedText(selX1, selY1, selX2, selY2).trim()
                        terminalView.termSession!!.onCopyTextToClipboard(selectedText)
                        terminalView.stopTextSelectionMode()
                    }

                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        // Pasting is input: it must reach the shell, not screen's pager
                        // (this path writes through the emulator, bypassing the view).
                        terminalView.exitScreenCopyModeIfNeeded()
                        terminalView.termSession!!.onPasteTextFromClipboard()
                    }

                    ACTION_MORE -> {
                        // Stop selecting first, otherwise the handles float above the popup.
                        terminalView.stopTextSelectionMode()
                        terminalView.showContextMenu()
                    }
                }

                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

        actionMode = terminalView.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                callback.onCreateActionMode(mode, menu)

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                callback.onActionItemClicked(mode, item)

            override fun onDestroyActionMode(mode: ActionMode) = Unit

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val lineSpacing = terminalView.renderer.fontLineSpacing
                var x1 = (selX1 * terminalView.renderer.fontWidth).roundToInt()
                var x2 = (selX2 * terminalView.renderer.fontWidth).roundToInt()
                val y1 = (selY1 - 1 - terminalView.topRow) * lineSpacing
                val y2 = (selY2 + 1 - terminalView.topRow) * lineSpacing

                if (x1 > x2) {
                    val tmp = x1
                    x1 = x2
                    x2 = tmp
                }

                outRect.set(x1, y1 + handleHeight, x2, y2 + handleHeight)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val emulator = terminalView.emulator!!
        val screen = emulator.screen
        val scrollRows = screen.activeRows - emulator.rows
        if (handle == startHandle) {
            selX1 = terminalView.getCursorX(x.toFloat())
            selY1 = terminalView.getCursorY(y.toFloat())
            if (selX1 < 0) {
                selX1 = 0
            }

            if (selY1 < -scrollRows) {
                selY1 = -scrollRows
            } else if (selY1 > emulator.rows - 1) {
                selY1 = emulator.rows - 1
            }

            if (selY1 > selY2) {
                selY1 = selY2
            }
            if (selY1 == selY2 && selX1 > selX2) {
                selX1 = selX2
            }

            if (!emulator.isAlternateBufferActive) {
                var topRow = terminalView.topRow

                if (selY1 <= topRow) {
                    topRow--
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows
                    }
                } else if (selY1 >= topRow + emulator.rows) {
                    topRow++
                    if (topRow > 0) {
                        topRow = 0
                    }
                }

                terminalView.topRow = topRow
            }

            selX1 = getValidCurX(screen, selY1, selX1)
        } else {
            selX2 = terminalView.getCursorX(x.toFloat())
            selY2 = terminalView.getCursorY(y.toFloat())
            if (selX2 < 0) {
                selX2 = 0
            }

            if (selY2 < -scrollRows) {
                selY2 = -scrollRows
            } else if (selY2 > emulator.rows - 1) {
                selY2 = emulator.rows - 1
            }

            if (selY1 > selY2) {
                selY2 = selY1
            }
            if (selY1 == selY2 && selX1 > selX2) {
                selX2 = selX1
            }

            if (!emulator.isAlternateBufferActive) {
                var topRow = terminalView.topRow

                if (selY2 <= topRow) {
                    topRow--
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows
                    }
                } else if (selY2 >= topRow + emulator.rows) {
                    topRow++
                    if (topRow > 0) {
                        topRow = 0
                    }
                }

                terminalView.topRow = topRow
            }

            selX2 = getValidCurX(screen, selY2, selX2)
        }

        terminalView.invalidate()
    }

    /** A column that falls inside a wide character snaps to the end of that character. */
    private fun getValidCurX(screen: TerminalBuffer, cy: Int, cx: Int): Int {
        val line = screen.getSelectedText(0, cy, cx, cy)
        if (line.isNotEmpty()) {
            var col = 0
            var i = 0
            val len = line.length
            while (i < len) {
                val ch1 = line[i]
                if (ch1.code == 0) {
                    break
                }

                val wc = if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    i += 2
                    WcWidth.width(Character.toCodePoint(ch1, line[i - 1]))
                } else {
                    i += 1
                    WcWidth.width(ch1.code)
                }

                val cend = col + wc
                if (cx > col && cx < cend) {
                    return cend
                }
                if (cend == col) {
                    return col
                }
                col = cend
            }
        }
        return cx
    }

    fun decrementYTextSelectionCursors(decrement: Int) {
        selY1 -= decrement
        selY2 -= decrement
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode()
        }
    }

    override fun onDetached() = Unit

    override fun isActive(): Boolean = isSelectingText

    /** Fills [sel] with [y1, y2, x1, x2]; a short array is left alone. */
    fun getSelectors(sel: IntArray) {
        if (sel.size != 4) {
            return
        }

        sel[0] = selY1
        sel[1] = selY2
        sel[2] = selX1
        sel[3] = selX2
    }

    fun getActionMode(): ActionMode? = actionMode

    /** True while the start handle is the one being dragged. */
    fun isSelectionStartDragged(): Boolean = startHandle.isDragging

    /** True while the end handle is the one being dragged. */
    fun isSelectionEndDragged(): Boolean = endHandle.isDragging
}
