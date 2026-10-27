package com.sangiorgisrl.screenlink.terminal.view

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.sangiorgisrl.screenlink.terminal.emulator.TerminalEmulator
import com.sangiorgisrl.screenlink.terminal.emulator.TextStyle
import com.sangiorgisrl.screenlink.terminal.emulator.WcWidth
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Paints a [TerminalEmulator] onto a [Canvas].
 *
 * Font metrics are measured once in the constructor, so a new instance is needed
 * whenever the size or the typeface changes.
 */
class TerminalRenderer(val textSize: Int, val typeface: Typeface) {

    private val textPaint = Paint().apply {
        isAntiAlias = true
        this.typeface = this@TerminalRenderer.typeface
        this.textSize = this@TerminalRenderer.textSize.toFloat()
    }

    /** Width of one monospace cell, measured from a single 'X'. */
    val fontWidth: Float = textPaint.measureText("X")

    /** Row pitch in pixels. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    val fontLineSpacing: Int = ceil(textPaint.fontSpacing).toInt()

    /** Negative, the distance from the baseline up to the top of the glyphs. */
    private val fontAscent: Int = ceil(textPaint.ascent()).toInt()

    /** Baseline offset for the first row: [fontLineSpacing] + [fontAscent]. */
    val fontLineSpacingAndAscent: Int = fontLineSpacing + fontAscent

    /** Pre-measured widths of the printable ASCII range, so drawing does not re-measure. */
    private val asciiMeasures = FloatArray(127)

    init {
        val probe = StringBuilder(" ")
        for (codePoint in asciiMeasures.indices) {
            probe[0] = codePoint.toChar()
            asciiMeasures[codePoint] = textPaint.measureText(probe, 0, 1)
        }
    }

    /**
     * Draw the terminal with [topRow] at the top, highlighting the rectangle
     * (selX1, selY1)..(selX2, selY2) when one is given.
     */
    fun render(
        emulator: TerminalEmulator,
        canvas: Canvas,
        topRow: Int,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int,
    ) {
        val reverseVideo = emulator.isReverseVideo
        val endRow = topRow + emulator.rows
        val columns = emulator.columns
        val cursorCol = emulator.cursorCol
        val cursorRow = emulator.cursorRow
        val cursorVisible = emulator.shouldCursorBeVisible()
        val screen = emulator.screen
        val palette = emulator.colors.currentColors
        val cursorShape = emulator.cursorStyle

        if (reverseVideo) {
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC)
        }

        var heightOffset = fontLineSpacingAndAscent.toFloat()
        for (row in topRow until endRow) {
            heightOffset += fontLineSpacing

            val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
            var selX1 = -1
            var selX2 = -1
            if (row in selectionY1..selectionY2) {
                if (row == selectionY1) selX1 = selectionX1
                selX2 = if (row == selectionY2) selectionX2 else columns
            }

            val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val line = lineObject.text
            val charsUsedInLine = lineObject.spaceUsed

            var lastRunStyle = 0L
            var lastRunInsideCursor = false
            var lastRunInsideSelection = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0
            var measuredWidthForRun = 0f

            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighSurrogate = Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighSurrogate) 2 else 1
                val codePoint = if (charIsHighSurrogate) {
                    Character.toCodePoint(charAtIndex, line[currentCharIndex + 1])
                } else {
                    charAtIndex.code
                }
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor = cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1)
                val insideSelection = column >= selX1 && column <= selX2
                val style = lineObject.getStyle(column)

                // The measured width can disagree with wcwidth() for fonts that are not
                // really monospace, or for glyphs android draws wide, like smileys. When
                // it does, the run is scaled so it still occupies wcwidth() cells.
                val measuredCodePointWidth = if (codePoint < asciiMeasures.size) {
                    asciiMeasures[codePoint]
                } else {
                    textPaint.measureText(line, currentCharIndex, charsForCodePoint)
                }
                val fontWidthMismatch = abs(measuredCodePointWidth / fontWidth - codePointWcWidth) > 0.01f

                if (style != lastRunStyle ||
                    insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection ||
                    fontWidthMismatch ||
                    lastRunFontWidthMismatch
                ) {
                    if (column != 0) {
                        // Nothing to draw in the very first column; only its style is recorded.
                        drawTextRun(
                            canvas, line, palette, heightOffset,
                            lastRunStartColumn, column - lastRunStartColumn,
                            lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                            measuredWidthForRun,
                            cursorColorFor(lastRunInsideCursor, palette),
                            cursorShape, lastRunStyle,
                            reverseVideo || invertCursorTextColor(lastRunInsideCursor, cursorShape) || lastRunInsideSelection,
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunStyle = style
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                // Fold combining characters into the run of the base character before
                // them, so they are not mistaken for being under the cursor.
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += if (Character.isHighSurrogate(line[currentCharIndex])) 2 else 1
                }
            }

            drawTextRun(
                canvas, line, palette, heightOffset,
                lastRunStartColumn, columns - lastRunStartColumn,
                lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                measuredWidthForRun,
                cursorColorFor(lastRunInsideCursor, palette),
                cursorShape, lastRunStyle,
                reverseVideo || invertCursorTextColor(lastRunInsideCursor, cursorShape) || lastRunInsideSelection,
            )
        }
    }

    private fun cursorColorFor(insideCursor: Boolean, palette: IntArray): Int =
        if (insideCursor) palette[TextStyle.COLOR_INDEX_CURSOR] else 0

    /** A block cursor paints the glyph in the background colour so it stays readable. */
    private fun invertCursorTextColor(insideCursor: Boolean, cursorShape: Int): Boolean =
        insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        palette: IntArray,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        measuredWidth: Float,
        cursor: Int,
        cursorStyle: Int,
        textStyle: Long,
        reverseVideo: Boolean,
    ) {
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
        val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
        val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
        val strikeThrough = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0
        val dim = (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0

        val opaque = OPAQUE_ALPHA
        if ((foreColor and opaque) != opaque) {
            // Bold promotes the eight low-intensity palette entries to their bright twins.
            if (bold && foreColor in 0..7) foreColor += 8
            foreColor = palette[foreColor]
        }

        if ((backColor and opaque) != opaque) {
            backColor = palette[backColor]
        }

        // Reverse video applies when _one and only one_ of the two flags is set:
        if (reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)) {
            val swapped = foreColor
            foreColor = backColor
            backColor = swapped
        }

        var left = startColumn * fontWidth
        var right = left + runWidthColumns * fontWidth

        var runWidthInCells = measuredWidth / fontWidth
        var savedMatrix = false
        if (abs(runWidthInCells - runWidthColumns) > 0.01f) {
            canvas.save()
            canvas.scale(runWidthColumns / runWidthInCells, 1f)
            left *= runWidthInCells / runWidthColumns
            right *= runWidthInCells / runWidthColumns
            savedMatrix = true
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only non-default backgrounds need a rectangle.
            textPaint.color = backColor
            canvas.drawRect(left, y - fontLineSpacingAndAscent + fontAscent, right, y, textPaint)
        }

        if (cursor != 0) {
            textPaint.color = cursor
            var cursorHeight = (fontLineSpacingAndAscent - fontAscent).toFloat()
            when (cursorStyle) {
                TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE -> cursorHeight /= 4f
                TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR -> right -= (right - left) * 3f / 4f
            }
            canvas.drawRect(left, y - cursorHeight, right, y, textPaint)
        }

        if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                // The dimming rule comes from libvte, which took it from xterm:
                // https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267
                val red = (0xFF and (foreColor shr 16)) * 2 / 3
                val green = (0xFF and (foreColor shr 8)) * 2 / 3
                val blue = (0xFF and foreColor) * 2 / 3
                foreColor = opaque or (red shl 16) or (green shl 8) or blue
            }

            textPaint.isFakeBoldText = bold
            textPaint.isUnderlineText = underline
            textPaint.textSkewX = if (italic) -0.35f else 0f
            textPaint.isStrikeThruText = strikeThrough
            textPaint.color = foreColor

            // Paint.Align.LEFT, which is the default.
            canvas.drawText(text, startCharIndex, runWidthChars, left, y - fontLineSpacingAndAscent, textPaint)
        }

        if (savedMatrix) canvas.restore()
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}
