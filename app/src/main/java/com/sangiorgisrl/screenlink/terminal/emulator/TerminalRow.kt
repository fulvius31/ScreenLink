package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import kotlin.math.max

/**
 * One row of a terminal: a fixed number of display cells.
 *
 * Cell contents live in [text] as a flat run of java chars, packed back to back rather
 * one-per-cell, because a cell can hold a surrogate pair, a wide character that spans two
 * cells, or a base character followed by combining marks. [findStartOfColumn] is the
 * bridge between a display column and the char index where that column begins.
 */
class TerminalRow(private val columns: Int, style: Long) {

    /**
     * The contents of the row, packed. Only the first [spaceUsed] entries are live; the
     * tail is spare capacity so that inserting a combining mark rarely reallocates.
     */
    @JvmField
    var text: CharArray = CharArray(max(1, (SPARE_CAPACITY_FACTOR * columns).toInt()))

    /** Set when the row wrapped because output landed in its last column. */
    @JvmField
    var lineWrap: Boolean = false

    /** The [TextStyle] word of every cell. Indexed by display column, not by char. */
    @JvmField
    val styles: LongArray = LongArray(columns)

    /** False while every cell is a single-width, non-surrogate char, which enables the fast path. */
    @JvmField
    var hasNonOneWidthOrSurrogateChars: Boolean = false

    private var usedChars: Short = 0

    /** How many entries of [text] are in use. */
    val spaceUsed: Int
        get() = usedChars.toInt()

    init {
        clear(style)
    }

    /** Copy columns [sourceX1, [sourceX2) of [line] to this row. Note that [sourceX2] is exclusive. */
    fun copyInterval(line: TerminalRow, sourceX1: Int, sourceX2: Int, destinationX: Int) {
        hasNonOneWidthOrSurrogateChars = hasNonOneWidthOrSurrogateChars || line.hasNonOneWidthOrSurrogateChars
        val x1 = line.findStartOfColumn(sourceX1)
        val x2 = line.findStartOfColumn(sourceX2)
        var fromSecondHalfOfWideChar = sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1)
        // Reading and writing the same row needs a snapshot, otherwise the copies below
        // would feed on their own output.
        val source = if (this === line) text.copyOf() else line.text

        var sourceColumn = sourceX1
        var targetColumn = destinationX
        var latestNonCombiningWidth = 0
        var i = x1
        while (i < x2) {
            val sourceChar = source[i]
            var codePoint =
                if (sourceChar.isHighSurrogate()) Character.toCodePoint(sourceChar, source[++i]) else sourceChar.code
            if (fromSecondHalfOfWideChar) {
                // The tail half of a wide character is copied in as a blank.
                codePoint = ' '.code
                fromSecondHalfOfWideChar = false
            }
            val width = WcWidth.width(codePoint)
            if (width > 0) {
                targetColumn += latestNonCombiningWidth
                sourceColumn += latestNonCombiningWidth
                latestNonCombiningWidth = width
            }
            setChar(targetColumn, codePoint, line.getStyle(sourceColumn))
            i++
        }
    }

    /**
     * The index into [text] where [column] begins. The column may be the right-hand half
     * of a wide character, in which case the start of that character is returned.
     */
    fun findStartOfColumn(column: Int): Int {
        if (column == columns) return spaceUsed

        var currentColumn = 0
        var currentCharIndex = 0
        while (true) {
            var charIndex = currentCharIndex
            val c = text[charIndex++]
            val codePoint = if (c.isHighSurrogate()) Character.toCodePoint(c, text[charIndex++]) else c.code
            val width = WcWidth.width(codePoint)
            if (width > 0) {
                currentColumn += width
                if (currentColumn == column) {
                    // Any combining marks belong to this column, so step over them too.
                    while (charIndex < spaceUsed) {
                        if (text[charIndex].isHighSurrogate()) {
                            val pair = Character.toCodePoint(text[charIndex], text[charIndex + 1])
                            if (WcWidth.width(pair) <= 0) charIndex += 2 else break
                        } else if (WcWidth.width(text[charIndex].code) <= 0) {
                            charIndex++
                        } else {
                            break
                        }
                    }
                    return charIndex
                } else if (currentColumn > column) {
                    // A wide character straddles the requested column.
                    return currentCharIndex
                }
            }
            currentCharIndex = charIndex
        }
    }

    private fun wideDisplayCharacterStartingAt(column: Int): Boolean {
        var charIndex = 0
        var currentColumn = 0
        while (charIndex < spaceUsed) {
            val c = text[charIndex++]
            val codePoint = if (c.isHighSurrogate()) Character.toCodePoint(c, text[charIndex++]) else c.code
            val width = WcWidth.width(codePoint)
            if (width > 0) {
                if (currentColumn == column && width == 2) return true
                currentColumn += width
                if (currentColumn > column) return false
            }
        }
        return false
    }

    fun clear(style: Long) {
        text.fill(' ')
        styles.fill(style)
        usedChars = columns.toShort()
        hasNonOneWidthOrSurrogateChars = false
    }

    /**
     * Write [codePoint] into [columnToSet] with the given style, reflowing the packed char
     * run when the new contents need more or fewer java chars than the old ones.
     *
     * Upstream notes: https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
     */
    fun setChar(columnToSet: Int, codePoint: Int, style: Long) {
        var column = columnToSet
        styles[column] = style

        val newWidth = WcWidth.width(codePoint)

        // Fast path: as long as the row is all single-width BMP chars, a column is its char.
        if (!hasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newWidth != 1) {
                hasNonOneWidthOrSurrogateChars = true
            } else {
                text[column] = codePoint.toChar()
                return
            }
        }

        val newIsCombining = newWidth <= 0
        val atSecondHalfOfWideChar = column > 0 && wideDisplayCharacterStartingAt(column - 1)

        if (newIsCombining) {
            // A combining mark written over the tail of a wide character attaches to that
            // character instead, so move one column left first.
            if (atSecondHalfOfWideChar) column--
        } else {
            if (atSecondHalfOfWideChar) setChar(column - 1, ' '.code, style)
            if (newWidth == 2 && wideDisplayCharacterStartingAt(column + 1)) setChar(column + 1, ' '.code, style)
        }

        var chars = text
        val oldStart = findStartOfColumn(column)
        val oldWidth = WcWidth.width(chars, oldStart)

        // How many java chars the column holds today.
        val oldCharsInColumn = if (column + oldWidth < columns) {
            findStartOfColumn(column + oldWidth) - oldStart
        } else {
            spaceUsed - oldStart
        }

        // How many java chars it will hold.
        var newCharsInColumn = Character.charCount(codePoint)
        if (newIsCombining) {
            // Combining marks extend the column instead of replacing it, so an unbounded
            // stream of them from a remote would make this row grow forever.
            if (oldCharsInColumn >= MAX_JAVA_CHARS_PER_COLUMN) return
            newCharsInColumn += oldCharsInColumn
        }

        val oldNextStart = oldStart + oldCharsInColumn
        val newNextStart = oldStart + newCharsInColumn
        val charDifference = newCharsInColumn - oldCharsInColumn

        if (charDifference > 0) {
            val charsAfter = spaceUsed - oldNextStart
            if (spaceUsed + charDifference > chars.size) {
                val grown = CharArray(chars.size + columns)
                chars.copyInto(grown, 0, 0, oldStart + oldCharsInColumn)
                chars.copyInto(grown, newNextStart, oldNextStart, oldNextStart + charsAfter)
                chars = grown
                text = chars
            } else {
                chars.copyInto(chars, newNextStart, oldNextStart, oldNextStart + charsAfter)
            }
        } else if (charDifference < 0) {
            chars.copyInto(chars, newNextStart, oldNextStart, oldNextStart + (spaceUsed - oldNextStart))
        }
        usedChars = (usedChars + charDifference).toShort()

        // A combining mark goes at the end of the column so that it modifies what is there.
        Character.toChars(codePoint, chars, oldStart + (if (newIsCombining) oldCharsInColumn else 0))

        if (oldWidth == 2 && newWidth == 1) {
            // The freed second half of the wide character becomes an explicit blank.
            if (spaceUsed + 1 > chars.size) {
                val grown = CharArray(chars.size + columns)
                chars.copyInto(grown, 0, 0, newNextStart)
                chars.copyInto(grown, newNextStart + 1, newNextStart, newNextStart + (spaceUsed - newNextStart))
                chars = grown
                text = chars
            } else {
                chars.copyInto(chars, newNextStart + 1, newNextStart, newNextStart + (spaceUsed - newNextStart))
            }
            chars[newNextStart] = ' '
            usedChars = (usedChars + 1).toShort()
        } else if (oldWidth == 1 && newWidth == 2) {
            when {
                column == columns - 1 -> throw IllegalArgumentException("Cannot put wide character in last column")
                // The wide character does not fit, so drop everything after it.
                column == columns - 2 -> usedChars = newNextStart.toShort()
                else -> {
                    // The wide character swallows the next column, freeing its chars.
                    val nextStart = newNextStart + (if (chars[newNextStart].isHighSurrogate()) 2 else 1)
                    val swallowed = nextStart - newNextStart
                    chars.copyInto(chars, newNextStart, nextStart, nextStart + (spaceUsed - nextStart))
                    usedChars = (usedChars - swallowed).toShort()
                }
            }
        }
    }

    fun isBlank(): Boolean {
        for (i in 0 until spaceUsed) if (text[i] != ' ') return false
        return true
    }

    fun getStyle(column: Int): Long = styles[column]

    companion object {
        private const val SPARE_CAPACITY_FACTOR = 1.5f

        /**
         * Ceiling on the java chars stored for one display column (base character plus
         * combining marks). Without it, a remote that floods a single cell with zero-width
         * marks grows [text] without bound and turns every later [setChar] on the row into
         * an O(n) memmove, stalling the decode thread.
         */
        private const val MAX_JAVA_CHARS_PER_COLUMN = 9
    }
}
