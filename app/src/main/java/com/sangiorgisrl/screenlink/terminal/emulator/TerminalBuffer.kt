package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import kotlin.math.max

/**
 * A circular buffer of [TerminalRow]s that keeps track of what is visible on the logical
 * screen and of the scrollback above it.
 *
 * Rows are addressed through the external coordinate system, where the visible screen is
 * `0..screenRows-1` and the transcript above it is negative; [externalToInternalRow] maps
 * that onto the ring buffer.
 */
class TerminalBuffer(columns: Int, totalRows: Int, screenRows: Int) {

    var columns = columns
        private set

    var totalRows = totalRows
        private set

    var screenRows = screenRows
        private set

    /** Rows currently held above the top of the screen. */
    var activeTranscriptRows = 0
        private set

    /** Ring-buffer index where the visible screen begins. */
    private var screenFirstRow = 0

    private var lines: Array<TerminalRow?> = arrayOfNulls(totalRows)

    /**
     * A transcript-backed screen.
     *
     * @param columns the width of the screen in characters.
     * @param totalRows the height of the whole text area, transcript included.
     * @param screenRows the height of the visible screen only.
     */
    init {
        blockSet(0, 0, columns, screenRows, ' '.code, TextStyle.NORMAL)
    }

    /** Everything on screen plus the scrollback above it. */
    val transcriptText: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows).trim()

    val transcriptTextWithoutJoinedLines: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows, false).trim()

    val transcriptTextWithFullLinesJoined: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows, true, true).trim()

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int): String =
        getSelectedText(selX1, selY1, selX2, selY2, true)

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean): String {
        // Local fix: upstream passed a literal `true` here, which silently ignored the
        // caller's joinBackLines flag (and made getTranscriptTextWithoutJoinedLines()
        // return joined text).
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false)
    }

    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean,
        joinFullLines: Boolean,
    ): String {
        val builder = StringBuilder()
        val width = columns
        var firstRow = selY1
        var lastRow = selY2

        if (firstRow < -activeTranscriptRows) firstRow = -activeTranscriptRows
        if (lastRow >= screenRows) lastRow = screenRows - 1

        var row = firstRow
        while (row <= lastRow) {
            val x1 = if (row == firstRow) selX1 else 0
            val x2 = if (row == lastRow) minOf(selX2 + 1, width) else width

            val line = lines[externalToInternalRow(row)]!!
            val x1Index = line.findStartOfColumn(x1)
            var x2Index = if (x2 < columns) line.findStartOfColumn(x2) else line.spaceUsed
            if (x2Index == x1Index) {
                // The selection starts inside a wide character.
                x2Index = line.findStartOfColumn(x2 + 1)
            }

            val chars = line.text
            var lastPrintingCharIndex = -1
            val rowLineWrap = getLineWrap(row)
            if (rowLineWrap && x2 == width) {
                // A wrapped line keeps its trailing space, so nothing is trimmed.
                lastPrintingCharIndex = x2Index - 1
            } else {
                for (i in x1Index until x2Index) if (chars[i] != ' ') lastPrintingCharIndex = i
            }

            if (lastPrintingCharIndex != -1) {
                builder.append(chars, x1Index, lastPrintingCharIndex - x1Index + 1)
            }

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if (!joinBackLines || !rowLineWrap) {
                if (!joinFullLines || !lineFillsWidth) {
                    if (row < lastRow && row < screenRows - 1) builder.append('\n')
                }
            }
            row++
        }
        return builder.toString()
    }

    /** The word under `(x, y)`, where a word spans every row a wrapped line was laid out over. */
    fun getWordAtLocation(x: Int, y: Int): String {
        var firstRow = y
        var lastRow = y
        while (firstRow > 0 && !getSelectedText(0, firstRow - 1, columns, y, true, true).contains('\n')) {
            firstRow--
        }
        while (lastRow < screenRows && !getSelectedText(0, y, columns, lastRow + 1, true, true).contains('\n')) {
            lastRow++
        }

        val text = getSelectedText(0, firstRow, columns, lastRow, true, true)
        val textOffset = (y - firstRow) * columns + x
        if (textOffset >= text.length) {
            // The click landed to the right of the last word on the line.
            return ""
        }

        val before = text.lastIndexOf(' ', textOffset)
        var after = text.indexOf(' ', textOffset)
        if (after == -1) after = text.length
        if (before == after) {
            // The click landed on a space.
            return ""
        }
        return text.substring(before + 1, after)
    }

    val activeRows: Int
        get() = activeTranscriptRows + screenRows

    /**
     * Convert a row from the public external coordinate system to the private internal one.
     *
     * Externally the screen is `0..screenRows-1` with the transcript at negative rows.
     * Internally the [screenRows] rows starting at [screenFirstRow] are the screen, and the
     * [activeTranscriptRows] rows ending just before it are the transcript.
     */
    fun externalToInternalRow(externalRow: Int): Int {
        if (externalRow < -activeTranscriptRows || externalRow > screenRows) {
            throw IllegalArgumentException(
                "extRow=$externalRow, screenRows=$screenRows, activeTranscriptRows=$activeTranscriptRows",
            )
        }
        val internalRow = screenFirstRow + externalRow
        return if (internalRow < 0) totalRows + internalRow else internalRow % totalRows
    }

    fun setLineWrap(row: Int) {
        lines[externalToInternalRow(row)]!!.lineWrap = true
    }

    fun getLineWrap(row: Int): Boolean = lines[externalToInternalRow(row)]!!.lineWrap

    fun clearLineWrap(row: Int) {
        lines[externalToInternalRow(row)]!!.lineWrap = false
    }

    /**
     * Resize the screen this transcript backs. This only works when the column count is
     * unchanged or the rows grow - that is, when shrinking the row count.
     *
     * @param cursor the (column, row) cursor position, updated in place.
     */
    fun resize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: IntArray,
        currentStyle: Long,
        altScreen: Boolean,
    ) {
        // newRows > totalRows should not normally happen since totalRows is the transcript size.
        if (newColumns == columns && newRows <= totalRows) {
            // Fast path: only the row count changed.
            var shiftDownOfTopRow = screenRows - newRows
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < screenRows) {
                // Shrinking: see whether blank rows below the cursor can simply be dropped.
                for (i in screenRows - 1 downTo 1) {
                    if (cursor[1] >= i) break
                    val r = externalToInternalRow(i)
                    if (lines[r] == null || lines[r]!!.isBlank()) {
                        shiftDownOfTopRow--
                        if (shiftDownOfTopRow == 0) break
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Expanding: only pull the screen up as far as the transcript can fill it.
                val actualShift = max(shiftDownOfTopRow, -activeTranscriptRows)
                if (shiftDownOfTopRow != actualShift) {
                    // The rows revealed are not all from the transcript, so blank the rest.
                    for (i in 0 until actualShift - shiftDownOfTopRow) {
                        allocateFullLineIfNecessary((screenFirstRow + screenRows + i) % totalRows).clear(currentStyle)
                    }
                    shiftDownOfTopRow = actualShift
                }
            }
            screenFirstRow += shiftDownOfTopRow
            screenFirstRow = if (screenFirstRow < 0) screenFirstRow + totalRows else screenFirstRow % totalRows
            totalRows = newTotalRows
            activeTranscriptRows = if (altScreen) 0 else max(0, activeTranscriptRows + shiftDownOfTopRow)
            cursor[1] -= shiftDownOfTopRow
            screenRows = newRows
        } else {
            // Slow path: rebuild the buffer and replay the old contents into it.
            val oldLines = lines
            lines = Array<TerminalRow?>(newTotalRows) { TerminalRow(newColumns, currentStyle) }

            val oldActiveTranscriptRows = activeTranscriptRows
            val oldScreenFirstRow = screenFirstRow
            val oldScreenRows = screenRows
            val oldTotalRows = totalRows
            totalRows = newTotalRows
            screenRows = newRows
            activeTranscriptRows = 0
            screenFirstRow = 0
            columns = newColumns

            var newCursorRow = -1
            var newCursorColumn = -1
            val oldCursorRow = cursor[1]
            val oldCursorColumn = cursor[0]
            var newCursorPlaced = false

            var outRow = 0
            var outColumn = 0

            // Walk every character of the old state. Blank rows are skipped only when they
            // sit at the end of the transcript, mirroring the fast path above, so the count
            // of skipped blanks is kept in case a non-blank row follows them.
            var skippedBlankLines = 0
            for (oldRow in -oldActiveTranscriptRows until oldScreenRows) {
                // Same mapping as externalToInternalRow(), but against the old geometry.
                var internalOldRow = oldScreenFirstRow + oldRow
                internalOldRow = if (internalOldRow < 0) oldTotalRows + internalOldRow else internalOldRow % oldTotalRows

                val oldLine = oldLines[internalOldRow]
                val cursorAtThisRow = oldRow == oldCursorRow
                // A row holding the cursor must never be skipped:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++
                    continue
                } else if (skippedBlankLines > 0) {
                    // A non-blank row followed the blanks, so re-insert them.
                    repeat(skippedBlankLines) {
                        if (outRow == screenRows - 1) {
                            scrollDownOneLine(0, screenRows, currentStyle)
                        } else {
                            outRow++
                        }
                        outColumn = 0
                    }
                    skippedBlankLines = 0
                }

                var lastNonSpaceIndex = 0
                var justToCursor = false
                if (cursorAtThisRow || oldLine.lineWrap) {
                    // Take the whole row, either because the cursor is on it or because it wraps.
                    lastNonSpaceIndex = oldLine.spaceUsed
                    if (cursorAtThisRow) justToCursor = true
                } else {
                    for (i in 0 until oldLine.spaceUsed) {
                        // NEWLY INTRODUCED BUG! Should not index oldLine.styles with char indices
                        if (oldLine.text[i] != ' ' /* || oldLine.styles[i] != currentStyle */) {
                            lastNonSpaceIndex = i + 1
                        }
                    }
                }

                var oldCol = 0
                var styleAtCol = 0L
                var i = 0
                while (i < lastNonSpaceIndex) {
                    // Note that this walks java characters, not cells.
                    val c = oldLine.text[i]
                    val codePoint = if (c.isHighSurrogate()) Character.toCodePoint(c, oldLine.text[++i]) else c.code
                    val displayWidth = WcWidth.width(codePoint)
                    // A zero-width character keeps the style of the character it combines with.
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(oldCol)

                    if (outColumn + displayWidth > columns) {
                        setLineWrap(outRow)
                        if (outRow == screenRows - 1) {
                            if (newCursorPlaced) newCursorRow--
                            scrollDownOneLine(0, screenRows, currentStyle)
                        } else {
                            outRow++
                        }
                        outColumn = 0
                    }

                    val offsetDueToCombiningChar = if (displayWidth <= 0 && outColumn > 0) 1 else 0
                    setChar(outColumn - offsetDueToCombiningChar, outRow, codePoint, styleAtCol)

                    if (displayWidth > 0) {
                        if (oldCursorRow == oldRow && oldCursorColumn == oldCol) {
                            newCursorColumn = outColumn
                            newCursorRow = outRow
                            newCursorPlaced = true
                        }
                        oldCol += displayWidth
                        outColumn += displayWidth
                        if (justToCursor && newCursorPlaced) break
                    }
                    i++
                }

                // The old row is copied; add a line break unless it was wrapping.
                if (oldRow != oldScreenRows - 1 && !oldLine.lineWrap) {
                    if (outRow == screenRows - 1) {
                        if (newCursorPlaced) newCursorRow--
                        scrollDownOneLine(0, screenRows, currentStyle)
                    } else {
                        outRow++
                    }
                    outColumn = 0
                }
            }

            cursor[0] = newCursorColumn
            cursor[1] = newCursorRow
        }

        // Keep the cursor on screen:
        if (cursor[0] < 0 || cursor[1] < 0) {
            cursor[0] = 0
            cursor[1] = 0
        }
    }

    /**
     * Block copy rows, together with their metadata, inside the ring buffer, honouring
     * wraparound.
     */
    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val total = totalRows

        val start = len - 1
        // Save the row that is about to be overwritten.
        val lineToBeOverWritten = lines[(srcInternal + start + 1) % total]
        // Copy from bottom to top so nothing is clobbered first.
        for (i in start downTo 0) {
            lines[(srcInternal + i + 1) % total] = lines[(srcInternal + i) % total]
        }
        // The saved row ends up above the moved block.
        lines[srcInternal % total] = lineToBeOverWritten
    }

    /**
     * Scroll the screen down one line. To scroll a whole 24-row screen the arguments are (0, 24).
     *
     * @param topMargin first row that is scrolled.
     * @param bottomMargin one row past the last row that is scrolled.
     * @param style the style of the newly exposed row.
     */
    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: Long) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > screenRows) {
            throw IllegalArgumentException("topMargin=$topMargin, bottomMargin=$bottomMargin, screenRows=$screenRows")
        }

        // Move the rows above the margin down so they stay where they are.
        blockCopyLinesDown(screenFirstRow, topMargin)
        // Likewise the rows below the margin.
        blockCopyLinesDown(externalToInternalRow(bottomMargin), screenRows - bottomMargin)

        // Advance the screen inside the ring buffer.
        screenFirstRow = (screenFirstRow + 1) % totalRows
        // The history grows until it is full.
        if (activeTranscriptRows < totalRows - screenRows) activeTranscriptRows++

        // Blank the row just above the bottom margin.
        val blankRow = externalToInternalRow(bottomMargin - 1)
        val existing = lines[blankRow]
        if (existing == null) {
            lines[blankRow] = TerminalRow(columns, style)
        } else {
            existing.clear(style)
        }
    }

    /**
     * Block copy characters from one place on the screen to another; the two may overlap.
     * Both must stay inside the screen bounds.
     */
    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        if (sx < 0 || sx + w > columns || sy < 0 || sy + h > screenRows || dx < 0 || dx + w > columns || dy < 0 || dy + h > screenRows) {
            throw IllegalArgumentException()
        }
        val copyingUp = sy > dy
        for (y in 0 until h) {
            // Traverse in the direction that avoids overwriting unread source.
            val y2 = if (copyingUp) y else h - (y + 1)
            val sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2))
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx)
        }
    }

    /**
     * Block set characters. All must stay inside the screen bounds. Usually called with a
     * `val` of 32 to blank a block.
     */
    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, codePoint: Int, style: Long) {
        if (sx < 0 || sx + w > columns || sy < 0 || sy + h > screenRows) {
            throw IllegalArgumentException(
                "Illegal arguments! blockSet($sx, $sy, $w, $h, $codePoint, $columns, $screenRows)",
            )
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                setChar(sx + x, sy + y, codePoint, style)
            }
        }
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        val existing = lines[row]
        if (existing != null) return existing
        return TerminalRow(columns, 0L).also { lines[row] = it }
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: Long) {
        if (row >= screenRows || column >= columns) {
            throw IllegalArgumentException("row=$row, column=$column, screenRows=$screenRows, columns=$columns")
        }
        allocateFullLineIfNecessary(externalToInternalRow(row)).setChar(column, codePoint, style)
    }

    fun getStyleAt(externalRow: Int, column: Int): Long =
        allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    fun setOrClearEffect(
        bits: Int,
        setOrClear: Boolean,
        reverse: Boolean,
        rectangular: Boolean,
        leftMargin: Int,
        rightMargin: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    ) {
        for (y in top until bottom) {
            val line = lines[externalToInternalRow(y)]!!
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine until endOfLine) {
                val currentStyle = line.getStyle(x)
                val foreColor = TextStyle.decodeForeColor(currentStyle)
                val backColor = TextStyle.decodeBackColor(currentStyle)
                var effect = TextStyle.decodeEffect(currentStyle)
                if (reverse) {
                    // Clear the bits being reversed, then add them back inverted.
                    effect = effect and bits.inv() or (bits and effect.inv())
                } else if (setOrClear) {
                    effect = effect or bits
                } else {
                    effect = effect and bits.inv()
                }
                line.styles[x] = TextStyle.encode(foreColor, backColor, effect)
            }
        }
    }

    fun clearTranscript() {
        if (screenFirstRow < activeTranscriptRows) {
            lines.fill(null, totalRows + screenFirstRow - activeTranscriptRows, totalRows)
            lines.fill(null, 0, screenFirstRow)
        } else {
            lines.fill(null, screenFirstRow - activeTranscriptRows, screenFirstRow)
        }
        activeTranscriptRows = 0
    }
}
