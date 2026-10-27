package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Stack
import kotlin.math.max
import kotlin.math.min

/**
 * Renders text into a screen: all the terminal-specific knowledge and state lives here.
 *
 * It emulates a subset of the X Window System xterm terminal, which in turn emulates a
 * subset of the DEC vt100.
 *
 * References:
 *  - http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
 *  - http://en.wikipedia.org/wiki/ANSI_escape_code
 *  - http://man.he.net/man4/console_codes
 *  - http://www.columbia.edu/~kermit/k95manual/iso2022.html
 *  - http://www.vt100.net/docs/vt510-rm/chapter4
 *  - http://en.wikipedia.org/wiki/ISO/IEC_2022 - for the 7-bit GL / 8-bit GR explanation
 */
class TerminalEmulator(
    private val session: TerminalOutput,
    columns: Int,
    rows: Int,
    transcriptRows: Int?,
    private var client: TerminalSessionClient?,
) {

    /**
     * The title as last set by the remote.
     *
     * Volatile because it is written while appending output (the SSH reader thread)
     * and read from the UI thread through [title] without holding the emulator lock:
     * a plain field is a data race, and a torn read here shows up as a title that
     * never arrives rather than as a crash.
     */
    @Volatile
    private var titleOrNull: String? = null
    private val titleStack = Stack<String?>()

    /** The cursor position, somewhere between (0, 0) and (rows - 1, columns - 1). */
    var cursorRow = 0
        private set

    var cursorCol = 0
        private set

    /** The size of the emulated screen, in characters. */
    var rows = rows
        private set

    var columns = columns
        private set

    /** One of [TERMINAL_CURSOR_STYLES_LIST]. */
    var cursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE
        private set

    /** The normal screen buffer: what is visible, plus the transcript above it. */
    private val mainBuffer = TerminalBuffer(columns, transcriptRowsOrDefault(transcriptRows), rows)

    /**
     * The alternate screen buffer, exactly as large as the display and with no extra saved
     * lines, so that while it is active there is nothing to scroll back to.
     *
     * See http://www.xfree86.org/current/ctlseqs.html#The%20Alternate%20Screen%20Buffer
     */
    private val altBuffer = TerminalBuffer(columns, transcriptRowsOrDefault(DEFAULT_TERMINAL_TRANSCRIPT_ROWS), rows)

    /** The screen in use: either [mainBuffer] or [altBuffer]. */
    var screen = mainBuffer
        private set

    /** Index of the argument being collected in the escape sequence under construction. */
    private var argIndex = 0

    /** The arguments of the escape sequence under construction. */
    private val args = IntArray(MAX_ESCAPE_PARAMETERS)

    /** OSC and device control payloads, which are strings rather than numbers. */
    private val oscOrDeviceControlArgs = StringBuilder()

    /** Whether the escape sequence being parsed survives this character. */
    private var sequenceShouldContinue = false

    /** Escape state machine, one of the `ESC_*` constants. */
    private var escapeState = ESC_NONE

    private val savedStateMain = SavedScreenState()
    private val savedStateAlt = SavedScreenState()

    /** http://www.vt100.net/docs/vt102-ug/table5-15.html */
    private var useLineDrawingG0 = false
    private var useLineDrawingG1 = false
    private var useLineDrawingUsesG0 = true

    /** @see mapDecSetBitToInternalBit */
    private var currentDecSetFlags = 0

    private var savedDecSetFlags = 0

    /**
     * Insert mode as opposed to replace mode: new characters push the existing text to the
     * right, and anything pushed past the right margin is lost.
     */
    private var insertMode = false

    /** [tabStop][i] is true where a tab stop is set. */
    private var tabStop = BooleanArray(columns)

    /**
     * Scrolling region: top in 0..rows-2, bottom one row past the region, left and right
     * margins in [0, columns].
     */
    private var topMargin = 0

    private var bottomMargin = 0
    private var leftMargin = 0
    private var rightMargin = 0

    /**
     * True when the *next* character will wrap to the next line. Standing on the last
     * column, a written character is output there without moving the cursor but sets this
     * flag; the character after that one moves to the next line.
     */
    private var aboutToAutoWrap = false

    /** Cursor blinking, which additionally requires the cursor to be enabled. */
    private var blinkingEnabled = false

    /** Which half of the blink cycle the cursor is in when [blinkingEnabled] is set. */
    private var blinkState = false

    /**
     * Current foreground and background colours: either an index in [0, 258] or a
     * truecolour value with the top byte (0xff000000) set.
     *
     * @see TextStyle
     */
    private var foreColor = TextStyle.COLOR_INDEX_FOREGROUND
    private var backColor = TextStyle.COLOR_INDEX_BACKGROUND

    /** The current [TextStyle] effect flags. */
    private var effect = 0

    /**
     * Lines scrolled since the last [clearScrollCounter] call, so the renderer can move the
     * selection up along with the text.
     */
    var scrollCounter = 0
        private set

    private var utf8ToFollow = 0
    private var utf8Index = 0
    private val utf8InputBuffer = ByteArray(4)
    private var lastEmittedCodePoint = -1

    /** The live palette, which OSC 4 can repaint at any time. */
    @JvmField
    val colors = TerminalColors()

    init {
        reset()
    }

    private fun isDecSetBitSet(bit: Int): Boolean = currentDecSetFlags and bit != 0

    private fun setDecSetBit(internalBit: Int, set: Boolean) {
        if (set) {
            // The mouse modes are mutually exclusive.
            when (internalBit) {
                DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE -> setDecSetBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
                DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT -> setDecSetBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
            }
            currentDecSetFlags = currentDecSetFlags or internalBit
        } else {
            currentDecSetFlags = currentDecSetFlags and internalBit.inv()
        }
    }

    private fun transcriptRowsOrDefault(requested: Int?): Int =
        if (requested == null || requested < TERMINAL_TRANSCRIPT_ROWS_MIN || requested > TERMINAL_TRANSCRIPT_ROWS_MAX) {
            DEFAULT_TERMINAL_TRANSCRIPT_ROWS
        } else {
            requested
        }

    fun updateTerminalSessionClient(newClient: TerminalSessionClient) {
        client = newClient
        refreshCursorStyle()
        blinkState = true
    }

    /** True while the alternate screen buffer is the one being drawn. */
    val isAlternateBufferActive: Boolean get() = screen === altBuffer

    /**
     * @param mouseButton one of the `MOUSE_*` constants of this class.
     */
    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        val col = column.coerceIn(1, columns)
        val line = row.coerceIn(1, rows)

        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && !isDecSetBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
            // Not reporting motion.
            return
        }

        if (isDecSetBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
            session.write(String.format("\u001B[<%d;%d;%d" + if (pressed) "M" else "m", mouseButton, col, line))
            return
        }

        val button = if (pressed) mouseButton else 3 // 3 releases all buttons.

        // Clip to the limits of the 8-bit encoding this protocol uses.
        if (col <= 255 - 32 && line <= 255 - 32) {
            val data = byteArrayOf(
                0x1B,
                '['.code.toByte(),
                'M'.code.toByte(),
                (32 + button).toByte(),
                (32 + col).toByte(),
                (32 + line).toByte(),
            )
            session.write(data, 0, data.size)
        }
    }

    @Synchronized
    fun resize(columns: Int, rows: Int) {
        if (this.rows == rows && this.columns == columns) return
        require(columns >= 2 && rows >= 2) { "rows=$rows, columns=$columns" }

        if (this.rows != rows) {
            this.rows = rows
            topMargin = 0
            bottomMargin = this.rows
        }
        if (this.columns != columns) {
            val oldColumns = this.columns
            this.columns = columns
            val oldTabStops = tabStop
            tabStop = BooleanArray(this.columns)
            setDefaultTabStops()
            oldTabStops.copyInto(tabStop, 0, 0, min(oldColumns, columns))
            leftMargin = 0
            rightMargin = this.columns
        }

        resizeScreen()
    }

    private fun resizeScreen() {
        val cursor = intArrayOf(cursorCol, cursorRow)
        // Keep each buffer's own transcript capacity across resizes. The alternate buffer
        // keeps a transcript of its own, so touch-scrolling works inside a full-screen TUI;
        // passing the screen height plus altScreen=true used to wipe that transcript on any
        // row-only resize and collapse the buffer to screen height.
        screen.resize(columns, rows, screen.totalRows, cursor, currentStyle, false)
        cursorCol = cursor[0]
        cursorRow = cursor[1]
    }

    /** Refresh the cursor style from the client, falling back to the default. */
    private fun refreshCursorStyle() {
        val requested = client?.getTerminalCursorStyle()
        cursorStyle = if (requested == null || requested !in TERMINAL_CURSOR_STYLES_LIST) {
            DEFAULT_TERMINAL_CURSOR_STYLE
        } else {
            requested
        }
    }

    val isReverseVideo: Boolean get() = isDecSetBitSet(DECSET_BIT_REVERSE_VIDEO)

    val isCursorEnabled: Boolean get() = isDecSetBitSet(DECSET_BIT_CURSOR_ENABLED)

    fun shouldCursorBeVisible(): Boolean =
        if (!isCursorEnabled) false else if (blinkingEnabled) blinkState else true

    fun setCursorBlinkingEnabled(enabled: Boolean) {
        blinkingEnabled = enabled
    }

    fun setCursorBlinkState(state: Boolean) {
        blinkState = state
    }

    val isKeypadApplicationMode: Boolean get() = isDecSetBitSet(DECSET_BIT_APPLICATION_KEYPAD)

    val isCursorKeysApplicationMode: Boolean get() = isDecSetBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS)

    /** True after the remote asked for xterm alternate scroll with `ESC[?1007h`. */
    val isAlternateScrollActive: Boolean get() = isDecSetBitSet(DECSET_BIT_ALTERNATE_SCROLL)

    /** Whether mouse events are being reported as escape codes to the remote. */
    val isMouseTrackingActive: Boolean
        get() = isDecSetBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || isDecSetBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)

    private fun setDefaultTabStops() {
        for (i in 0 until columns) {
            tabStop[i] = i and 7 == 0 && i != 0
        }
    }

    /**
     * Accept bytes - typically from the SSH channel - and process them.
     *
     * Synchronized against [resize]: the reader thread decodes into the buffers while the UI
     * thread may be reallocating them, and running the two unsynchronized could corrupt the
     * transcript state.
     *
     * @param buffer the bytes to process.
     * @param length how many bytes at the start of [buffer] to process.
     */
    @Synchronized
    fun append(buffer: ByteArray, length: Int) {
        for (i in 0 until length) {
            processByte(buffer[i])
        }
    }

    private fun processByte(byteToProcess: Byte) {
        val asInt = byteToProcess.toInt()
        if (utf8ToFollow > 0) {
            if (asInt and 0b11000000 == 0b10000000) {
                // 10xxxxxx: a continuation byte.
                utf8InputBuffer[utf8Index++] = byteToProcess
                if (--utf8ToFollow == 0) {
                    val firstByteMask =
                        if (utf8Index == 2) 0b00011111 else if (utf8Index == 3) 0b00001111 else 0b00000111
                    var codePoint = utf8InputBuffer[0].toInt() and firstByteMask
                    for (i in 1 until utf8Index) {
                        codePoint = (codePoint shl 6) or (utf8InputBuffer[i].toInt() and 0b00111111)
                    }
                    val overlong = (codePoint <= 0b1111111 && utf8Index > 1) ||
                        (codePoint < 0b11111111111 && utf8Index > 2) ||
                        (codePoint < 0b1111111111111111 && utf8Index > 3)
                    if (overlong) {
                        codePoint = UNICODE_REPLACEMENT_CHAR
                    }

                    utf8Index = 0
                    utf8ToFollow = 0

                    if (codePoint in 0x80..0x9F) {
                        // A C1 control character, which we ignore: they are unused nowadays
                        // and make binary input easy to misread. XTerm does not allow them
                        // in utf-8 either - "It is not possible to use a C1 control obtained
                        // from decoding the UTF-8 text",
                        // http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
                    } else {
                        when (Character.getType(codePoint)) {
                            Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt() -> codePoint = UNICODE_REPLACEMENT_CHAR
                        }
                        processCodePoint(codePoint)
                    }
                }
            } else {
                // Not a continuation byte, so the whole sequence so far becomes the
                // replacement character. Unicode 6.2 core spec, ch03: a converter must not
                // consume successor bytes that are themselves a well-formed sequence, so
                // reprocess this one.
                utf8Index = 0
                utf8ToFollow = 0
                emitCodePoint(UNICODE_REPLACEMENT_CHAR)
                processByte(byteToProcess)
            }
        } else {
            when {
                // The leading bit is clear: 7-bit ASCII.
                asInt and 0b10000000 == 0 -> {
                    processCodePoint(byteToProcess.toInt())
                    return
                }
                asInt and 0b11100000 == 0b11000000 -> utf8ToFollow = 1 // 110xxxxx: two bytes.
                asInt and 0b11110000 == 0b11100000 -> utf8ToFollow = 2 // 1110xxxx: three bytes.
                asInt and 0b11111000 == 0b11110000 -> utf8ToFollow = 3 // 11110xxx: four bytes.
                else -> {
                    // Not a valid sequence start.
                    processCodePoint(UNICODE_REPLACEMENT_CHAR)
                    return
                }
            }
            utf8InputBuffer[utf8Index++] = byteToProcess
        }
    }

    fun processCodePoint(b: Int) {
        when (b) {
            0 -> return // NUL: nothing at all.

            7 -> // BEL: may terminate an OSC string, otherwise it rings.
                if (escapeState == ESC_OSC) doOsc(b) else session.onBell()

            8 -> // BS.
                if (leftMargin == cursorCol) {
                    // Step back onto the previous line if it wrapped.
                    val previousRow = cursorRow - 1
                    if (previousRow >= 0 && screen.getLineWrap(previousRow)) {
                        screen.clearLineWrap(previousRow)
                        setCursorRowCol(previousRow, rightMargin - 1)
                    }
                } else {
                    setCursorCol(cursorCol - 1)
                }

            // HT: move to the next tab stop, but not past the edge of the screen. A tab only
            // moves the cursor, so it does not paint with the current colour.
            9 -> cursorCol = nextTabStop(1)

            10, 11, 12 -> doLinefeed() // LF, VT, FF.

            13 -> setCursorCol(leftMargin) // CR.

            14 -> useLineDrawingUsesG0 = false // SO: switch to the G1 character set.
            15 -> useLineDrawingUsesG0 = true // SI: switch back to G0.

            24, 26 -> { // CAN and SUB abort any sequence in progress.
                if (escapeState != ESC_NONE) {
                    escapeState = ESC_NONE
                    emitCodePoint(127)
                }
            }

            27 -> // ESC: starts a sequence unless we are inside a string.
                if (escapeState == ESC_P) {
                    // Ignore, it may be part of the string terminator.
                    return
                } else if (escapeState != ESC_OSC) {
                    startEscapeSequence()
                } else {
                    doOsc(b)
                }

            else -> {
                sequenceShouldContinue = false
                when (escapeState) {
                    ESC_NONE -> if (b >= 32) emitCodePoint(b)
                    ESC -> doEsc(b)
                    ESC_POUND -> doEscPound(b)
                    ESC_SELECT_LEFT_PAREN -> useLineDrawingG0 = b == '0'.code // Designate G0 (ISO 2022, VT100).
                    ESC_SELECT_RIGHT_PAREN -> useLineDrawingG1 = b == '0'.code // Designate G1.
                    ESC_CSI -> doCsi(b)
                    ESC_CSI_EXCLAMATION ->
                        // DECSTR, soft reset: http://vt100.net/docs/vt510-rm/DECSTR.
                        if (b == 'p'.code) reset() else unknownSequence(b)
                    ESC_CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    ESC_CSI_BIGGERTHAN -> doCsiBiggerThan(b)
                    ESC_CSI_DOLLAR -> doCsiDollar(b)
                    ESC_CSI_DOUBLE_QUOTE -> doCsiDoubleQuote(b)
                    ESC_CSI_SINGLE_QUOTE -> doCsiSingleQuote(b)
                    ESC_PERCENT -> Unit
                    ESC_OSC -> doOsc(b)
                    ESC_OSC_ESC -> doOscEsc(b)
                    ESC_P -> doDeviceControl(b)
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> doCsiQuestionMarkArgDollar(b)
                    ESC_CSI_ARGS_SPACE -> doCsiArgsSpace(b)
                    ESC_CSI_ARGS_ASTERIX -> {
                        val attributeChangeExtent = getArg0(0)
                        if (b == 'x'.code && attributeChangeExtent in 0..2) {
                            // DECSACE - http://www.vt100.net/docs/vt510-rm/DECSACE.
                            setDecSetBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attributeChangeExtent == 2)
                        } else {
                            unknownSequence(b)
                        }
                    }
                    else -> unknownSequence(b)
                }
                if (!sequenceShouldContinue) escapeState = ESC_NONE
            }
        }
    }

    /** While in [ESC_P], the "device control string" state. */
    private fun doDeviceControl(b: Int) {
        when (b.toChar()) {
            '\\' -> { // ST: the string is complete, act on it.
                val dcs = oscOrDeviceControlArgs.toString()
                when {
                    dcs.startsWith("\$q") ->
                        if (dcs == "\$q\"p") {
                            // DECSCL, conformance level: http://www.vt100.net/docs/vt510-rm/DECSCL.
                            session.write("\u001BP1\$r" + "64;1\"p" + "\u001B\\")
                        } else {
                            finishSequenceAndLogError("Unrecognized DECRQSS string: '$dcs'")
                        }

                    dcs.startsWith("+q") -> {
                        // Request Termcap/Terminfo string: the names after the "q" are hex
                        // encoded, two digits per character, separated by ';'. Two entries
                        // are not key names: Co/colors (termcap/terminfo colours) and
                        // TN/name (terminal name). xterm answers a valid request with
                        // DCS 1 + r P t ST, echoing P t and appending '=' plus the
                        // hex-encoded value, and an invalid one with DCS 0 + r P t ST.
                        //
                        // Example, requesting "ku": ESC P + q 6 b 7 5 ESC \, because
                        // 0x6b 0x75 = "ku". xterm answers in normal cursor mode with
                        // 0x1B 0x5B 0x41 (ESC [ A) and in application cursor mode with
                        // 0x1B 0x4F 0x41 (ESC O A).
                        //
                        // See KeyHandler for the sequences these names map to.
                        for (part in dcs.substring(2).split(";").dropLastWhile { it.isEmpty() }) {
                            if (part.length % 2 != 0) {
                                client?.logError(LOG_TAG, "Invalid device termcap/terminfo name of odd length: $part")
                                continue
                            }
                            val trans = buildString {
                                for (i in part.indices step 2) {
                                    append(java.lang.Long.decode("0x${part[i]}${part[i + 1]}").toChar())
                                }
                            }
                            val responseValue = when (trans) {
                                "Co", "colors" -> "256" // Number of colours.
                                "TN", "name" -> "xterm"
                                else -> KeyHandler.getCodeFromTermcap(
                                    trans,
                                    isDecSetBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                                    isDecSetBitSet(DECSET_BIT_APPLICATION_KEYPAD),
                                )
                            }
                            if (responseValue == null) {
                                when (trans) {
                                    "%1" -> Unit // Help key - ignore.
                                    "&8" -> Unit // Undo key - ignore.
                                    else -> client?.logWarn(LOG_TAG, "Unhandled termcap/terminfo name: '$trans'")
                                }
                                // Answer with "invalid request".
                                session.write("\u001BP0+r$part\u001B\\")
                            } else {
                                val hexEncoded = buildString {
                                    for (element in responseValue) {
                                        append(String.format("%02X", element.code))
                                    }
                                }
                                session.write("\u001BP1+r$part=$hexEncoded\u001B\\")
                            }
                        }
                    }

                    else -> if (LOG_ESCAPE_SEQUENCES) {
                        client?.logError(LOG_TAG, "Unrecognized device control string: $dcs")
                    }
                }
                finishSequence()
            }

            else ->
                if (oscOrDeviceControlArgs.length > MAX_OSC_STRING_LENGTH) {
                    // Too long: give up on it.
                    oscOrDeviceControlArgs.setLength(0)
                    finishSequence()
                } else {
                    oscOrDeviceControlArgs.appendCodePoint(b)
                    continueSequence(escapeState)
                }
        }
    }

    private fun nextTabStop(numTabs: Int): Int {
        var tabs = numTabs
        var i = cursorCol + 1
        while (i < columns) {
            if (tabStop[i] && --tabs == 0) return min(i, rightMargin)
            i++
        }
        return rightMargin - 1
    }

    /** While in [ESC_CSI_QUESTIONMARK], the "ESC [ ?" state. */
    private fun doCsiQuestionMark(b: Int) {
        when (b.toChar()) {
            // DECSED (erase in display) and DECSEL (erase in line), the selective variants
            // that skip protected characters.
            'J', 'K' -> {
                aboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1
                var startRow = -1
                var endCol = -1
                var endRow = -1
                val justRow = b == 'K'.code
                when (getArg0(0)) {
                    0 -> { // From the active position to the end, inclusive.
                        startCol = cursorCol
                        startRow = cursorRow
                        endCol = columns
                        endRow = if (justRow) cursorRow + 1 else rows
                    }
                    1 -> { // From the start to the active position, inclusive.
                        startCol = 0
                        startRow = if (justRow) cursorRow else 0
                        endCol = cursorCol + 1
                        endRow = cursorRow + 1
                    }
                    2 -> { // Everything.
                        startCol = 0
                        startRow = if (justRow) cursorRow else 0
                        endCol = columns
                        endRow = if (justRow) cursorRow + 1 else rows
                    }
                    else -> unknownSequence(b)
                }
                val style = currentStyle
                for (row in startRow until endRow) {
                    for (col in startCol until endCol) {
                        if (TextStyle.decodeEffect(screen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED == 0) {
                            screen.setChar(col, row, fillChar, style)
                        }
                    }
                }
            }

            'h', 'l' -> {
                if (argIndex >= args.size) argIndex = args.size - 1
                for (i in 0..argIndex) {
                    doDecSetOrReset(b == 'h'.code, args[i])
                }
            }

            'n' -> // Device Status Report, DEC specific.
                when (getArg0(-1)) {
                    6 -> // DECXCPR, extended cursor position; page 1.
                        session.write(String.format(Locale.US, "\u001B[?%d;%d;1R", cursorRow + 1, cursorCol + 1))
                    else -> {
                        finishSequence()
                        return
                    }
                }

            'r', 's' -> {
                if (argIndex >= args.size) argIndex = args.size - 1
                for (i in 0..argIndex) {
                    val externalBit = args[i]
                    val internalBit = mapDecSetBitToInternalBit(externalBit)
                    if (internalBit == -1) {
                        client?.logWarn(LOG_TAG, "Ignoring request to save/recall decset bit=$externalBit")
                    } else if (b == 's'.code) {
                        savedDecSetFlags = savedDecSetFlags or internalBit
                    } else {
                        doDecSetOrReset(savedDecSetFlags and internalBit != 0, externalBit)
                    }
                }
            }

            '$' -> {
                continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR)
                return
            }

            else -> parseArg(b)
        }
    }

    fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        mapDecSetBitToInternalBit(externalBit).takeIf { it != -1 }?.let { setDecSetBit(it, setting) }

        when (externalBit) {
            1 -> Unit // DECCKM, application cursor keys.

            3 -> {
                // DECCOLM: we do not actually switch to 132 columns, but the side effects
                // matter - "reset" relies on them.
                // "Sets the left, right, top and bottom scrolling margins to their default
                // positions":
                leftMargin = 0
                topMargin = 0
                bottomMargin = rows
                rightMargin = columns
                // "DECCOLM resets vertical split screen mode (DECLRMM) to unavailable":
                setDecSetBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                // "Erases all data in page memory":
                blockClear(0, 0, columns, rows)
                setCursorRowCol(0, 0)
            }

            4 -> Unit // DECSCLM scrolling mode: ignore.
            5 -> Unit // Reverse video: nothing to do here.

            6 -> if (setting) setCursorPosition(0, 0) // DECOM, origin mode.

            // Wrap-around, DECARM, X10 mouse reporting, cursor-blink control and showing or
            // hiding the cursor need no action of their own.
            7, 8, 9, 12, 25 -> client?.onTerminalCursorStateChange(setting)

            40 -> Unit // Allow 80 => 132 mode: ignore.
            45 -> Unit // TODO: reverse wrap-around.
            66 -> Unit // DECNKM, application keypad.

            69 -> // DECLRMM, left and right margin mode.
                if (!setting) {
                    leftMargin = 0
                    rightMargin = columns
                }

            1000, 1001, 1002, 1003, 1004, 1005, 1006, 1015, 1034 -> Unit

            1048 -> // DECSC / DECRC.
                if (setting) saveCursor() else restoreCursor()

            47, 1047, 1049 -> {
                // Switch to the alternate screen buffer (saving the cursor) or back to the
                // normal one (restoring it).
                val newScreen = if (setting) altBuffer else mainBuffer
                if (newScreen !== screen) {
                    val resized = !(newScreen.columns == columns && newScreen.screenRows == rows)
                    if (setting) saveCursor()
                    screen = newScreen
                    if (!setting) {
                        val col = savedStateMain.savedCursorCol
                        val row = savedStateMain.savedCursorRow
                        restoreCursor()
                        if (resized) {
                            // Put the cursor back where it was, unclipped; resizeScreen()
                            // is what brings it on screen.
                            cursorCol = col
                            cursorRow = row
                        }
                    }
                    if (resized) resizeScreen()
                    if (newScreen === altBuffer) {
                        newScreen.blockSet(0, 0, columns, rows, ' '.code, currentStyle)
                    }
                }
            }

            2004 -> Unit // Bracketed paste mode: the bit itself is enough.

            else -> unknownParameter(externalBit)
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        when (b.toChar()) {
            'c' ->
                // Secondary Device Attributes (DA2). The version field is what applications
                // actually read: tmux and vim both gate features on an xterm patch level, so
                // answer like xterm does.
                session.write("\u001B[>41;320;0c")

            'm' ->
                // CSI > Ps m sets one of the xterm resources modifyKeyboard,
                // modifyCursorKeys, modifyFunctionKeys or modifyOtherKeys; none of them
                // change what this view sends.
                // http://invisible-island.net/xterm/manpage/xterm.html#RESOURCES
                client?.logError(LOG_TAG, "(ignored) CSI > MODIFY RESOURCE: ${getArg0(-1)} to ${getArg1(-1)}")

            else -> parseArg(b)
        }
    }

    private fun startEscapeSequence() {
        escapeState = ESC
        argIndex = 0
        args.fill(-1)
    }

    private fun doLinefeed() {
        val belowScrollingRegion = cursorRow >= bottomMargin
        var newCursorRow = cursorRow + 1
        if (belowScrollingRegion) {
            // Move down without scrolling, as long as there is a row left.
            if (cursorRow != rows - 1) setCursorRow(newCursorRow)
        } else {
            if (newCursorRow == bottomMargin) {
                scrollDownOneLine()
                newCursorRow = bottomMargin - 1
            }
            setCursorRow(newCursorRow)
        }
    }

    private fun continueSequence(state: Int) {
        escapeState = state
        sequenceShouldContinue = true
    }

    private fun doEscPound(b: Int) {
        when (b.toChar()) {
            '8' -> // DEC screen alignment test: fill the screen with E's.
                screen.blockSet(0, 0, columns, rows, 'E'.code, currentStyle)
            else -> unknownSequence(b)
        }
    }

    /** While in [ESC]. */
    private fun doEsc(b: Int) {
        when (b.toChar()) {
            '#' -> continueSequence(ESC_POUND)
            '(' -> continueSequence(ESC_SELECT_LEFT_PAREN)
            ')' -> continueSequence(ESC_SELECT_RIGHT_PAREN)

            '6' -> // DECBI, back index: move left, inserting a blank column at the start.
                if (cursorCol > leftMargin) {
                    cursorCol--
                } else {
                    val height = bottomMargin - topMargin
                    screen.blockCopy(leftMargin, topMargin, rightMargin - leftMargin - 1, height, leftMargin + 1, topMargin)
                    screen.blockSet(leftMargin, topMargin, 1, height, ' '.code, TextStyle.encode(foreColor, backColor, 0))
                }

            '7' -> saveCursor() // DECSC.
            '8' -> restoreCursor() // DECRC.

            '9' -> // DECFI, forward index: move right, inserting a blank column at the end.
                if (cursorCol < rightMargin - 1) {
                    cursorCol++
                } else {
                    val height = bottomMargin - topMargin
                    screen.blockCopy(leftMargin + 1, topMargin, rightMargin - leftMargin - 1, height, leftMargin, topMargin)
                    screen.blockSet(rightMargin - 1, topMargin, 1, height, ' '.code, TextStyle.encode(foreColor, backColor, 0))
                }

            'c' -> { // RIS, reset to initial state.
                reset()
                mainBuffer.clearTranscript()
                blockClear(0, 0, columns, rows)
                setCursorPosition(0, 0)
            }

            'D' -> doLinefeed() // INDEX.

            'E' -> { // NEL, next line.
                setCursorCol(if (isDecSetBitSet(DECSET_BIT_ORIGIN_MODE)) leftMargin else 0)
                doLinefeed()
            }

            'F' -> setCursorRowCol(0, bottomMargin - 1) // Cursor to the lower-left corner.

            'H' -> tabStop[cursorCol] = true // Tab set.

            'M' ->
                // RI, reverse index: "move to the same horizontal position on the preceding
                // line; if at the top margin, scroll down instead".
                if (cursorRow <= topMargin) {
                    screen.blockCopy(0, topMargin, columns, bottomMargin - (topMargin + 1), 0, topMargin + 1)
                    blockClear(0, topMargin, columns)
                } else {
                    cursorRow--
                }

            'N', '0' -> Unit // SS2 and SS3: ignore.

            'P' -> { // Device control string.
                oscOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_P)
            }

            '[' -> continueSequence(ESC_CSI)

            '=' -> setDecSetBit(DECSET_BIT_APPLICATION_KEYPAD, true) // DECKPAM.

            ']' -> { // OSC.
                oscOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_OSC)
            }

            '>' -> setDecSetBit(DECSET_BIT_APPLICATION_KEYPAD, false) // DECKPNM.

            else -> unknownSequence(b)
        }
    }

    /** DECSC: http://www.vt100.net/docs/vt510-rm/DECSC. See [restoreCursor]. */
    private fun saveCursor() {
        val state = if (screen === mainBuffer) savedStateMain else savedStateAlt
        state.savedCursorRow = cursorRow
        state.savedCursorCol = cursorCol
        state.savedEffect = effect
        state.savedForeColor = foreColor
        state.savedBackColor = backColor
        state.savedDecFlags = currentDecSetFlags
        state.useLineDrawingG0 = useLineDrawingG0
        state.useLineDrawingG1 = useLineDrawingG1
        state.useLineDrawingUsesG0 = useLineDrawingUsesG0
    }

    /** DECRC: http://www.vt100.net/docs/vt510-rm/DECRC. See [saveCursor]. */
    private fun restoreCursor() {
        val state = if (screen === mainBuffer) savedStateMain else savedStateAlt
        setCursorRowCol(state.savedCursorRow, state.savedCursorCol)
        effect = state.savedEffect
        foreColor = state.savedForeColor
        backColor = state.savedBackColor
        val mask = DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE
        currentDecSetFlags = currentDecSetFlags and mask.inv() or (state.savedDecFlags and mask)
        useLineDrawingG0 = state.useLineDrawingG0
        useLineDrawingG1 = state.useLineDrawingG1
        useLineDrawingUsesG0 = state.useLineDrawingUsesG0
    }

    /** The current style word, from the colours and effects in force. */
    private val currentStyle: Long get() = TextStyle.encode(foreColor, backColor, effect)

    /** After a CSI - Control Sequence Introducer, "\u001B[". See [ESC_CSI]. */
    private fun doCsi(b: Int) {
        when (b.toChar()) {
            '!' -> continueSequence(ESC_CSI_EXCLAMATION)
            '"' -> continueSequence(ESC_CSI_DOUBLE_QUOTE)
            '\'' -> continueSequence(ESC_CSI_SINGLE_QUOTE)
            '$' -> continueSequence(ESC_CSI_DOLLAR)
            '*' -> continueSequence(ESC_CSI_ARGS_ASTERIX)

            '@' -> {
                // CSI Ps @ - insert Ps blank characters (ICH).
                aboutToAutoWrap = false
                val columnsAfterCursor = columns - cursorCol
                val spacesToInsert = min(getArg0(1), columnsAfterCursor)
                val charsToMove = columnsAfterCursor - spacesToInsert
                screen.blockCopy(cursorCol, cursorRow, charsToMove, 1, cursorCol + spacesToInsert, cursorRow)
                blockClear(cursorCol, cursorRow, spacesToInsert)
            }

            'A' -> setCursorRow(max(0, cursorRow - getArg0(1))) // CUU.
            'B' -> setCursorRow(min(rows - 1, cursorRow + getArg0(1))) // CUD.
            'C', 'a' -> setCursorCol(min(rightMargin - 1, cursorCol + getArg0(1))) // CUF / HPR.
            'D' -> setCursorCol(max(leftMargin, cursorCol - getArg0(1))) // CUB.
            'E' -> setCursorPosition(0, cursorRow + getArg0(1)) // CNL.
            'F' -> setCursorPosition(0, cursorRow - getArg0(1)) // CPL.
            'G' -> setCursorCol(min(max(1, getArg0(1)), columns) - 1) // CHA.

            'H', 'f' -> setCursorPosition(getArg1(1) - 1, getArg0(1) - 1) // CUP / HVP.

            'I' -> setCursorCol(nextTabStop(getArg0(1))) // CHT.

            'J' -> { // Erase in display; ED ignores the scrolling margins.
                when (getArg0(0)) {
                    0 -> { // From the active position to the end of the screen, inclusive.
                        blockClear(cursorCol, cursorRow, columns - cursorCol)
                        blockClear(0, cursorRow + 1, columns, rows - (cursorRow + 1))
                    }
                    1 -> { // From the start of the screen to the active position, inclusive.
                        blockClear(0, 0, columns, cursorRow)
                        blockClear(0, cursorRow, cursorCol + 1)
                    }
                    2 -> blockClear(0, 0, columns, rows) // The whole display; the cursor stays.
                    3 -> mainBuffer.clearTranscript() // Everything in the scrollback buffer.
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                aboutToAutoWrap = false
            }

            'K' -> { // Erase in line.
                when (getArg0(0)) {
                    0 -> blockClear(cursorCol, cursorRow, columns - cursorCol)
                    1 -> blockClear(0, cursorRow, cursorCol + 1)
                    2 -> blockClear(0, cursorRow, columns)
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                aboutToAutoWrap = false
            }

            'L' -> { // IL, insert lines.
                val linesAfterCursor = bottomMargin - cursorRow
                val linesToInsert = min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToInsert
                screen.blockCopy(0, cursorRow, columns, linesToMove, 0, cursorRow + linesToInsert)
                blockClear(0, cursorRow, columns, linesToInsert)
            }

            'M' -> { // DL, delete lines.
                aboutToAutoWrap = false
                val linesAfterCursor = bottomMargin - cursorRow
                val linesToDelete = min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToDelete
                screen.blockCopy(0, cursorRow + linesToDelete, columns, linesToMove, 0, cursorRow)
                blockClear(0, cursorRow + linesToMove, columns, linesToDelete)
            }

            'P' -> {
                // DCH: deleting fewer cells than requested when the right margin is closer.
                // Attributes travel with the characters; the gap left at the margin is blank
                // and unstyled. It has no effect outside the scrolling margins.
                aboutToAutoWrap = false
                val cellsAfterCursor = columns - cursorCol
                val cellsToDelete = min(getArg0(1), cellsAfterCursor)
                val cellsToMove = cellsAfterCursor - cellsToDelete
                screen.blockCopy(cursorCol + cellsToDelete, cursorRow, cellsToMove, 1, cursorCol, cursorRow)
                blockClear(cursorCol + cellsToMove, cursorRow, cellsToDelete)
            }

            'S' -> { // SU, scroll up.
                repeat(getArg0(1)) { scrollDownOneLine() }
            }

            'T' ->
                if (argIndex == 0) {
                    // SD, scroll down: N new lines at the top, N old ones off the bottom,
                    // and never past the top margin.
                    val linesToScrollArg = getArg0(1)
                    val linesBetweenMargins = bottomMargin - topMargin
                    val linesToScroll = min(linesBetweenMargins, linesToScrollArg)
                    screen.blockCopy(0, topMargin, columns, linesBetweenMargins - linesToScroll, 0, topMargin + linesToScroll)
                    blockClear(0, topMargin, columns, linesToScroll)
                } else {
                    // CSI Ps ; Pu ; Px ; Py ; Pz ; Pt T - initiate highlight mouse tracking.
                    unimplementedSequence(b)
                }

            'X' -> { // ECH, erase Ps characters.
                aboutToAutoWrap = false
                screen.blockSet(cursorCol, cursorRow, min(getArg0(1), columns - cursorCol), 1, ' '.code, currentStyle)
            }

            'Z' -> { // CBT, cursor backward tabulation.
                var tabs = getArg0(1)
                var newCol = leftMargin
                var i = cursorCol - 1
                while (i >= 0) {
                    if (tabStop[i]) {
                        if (--tabs == 0) {
                            newCol = max(i, leftMargin)
                            break
                        }
                    }
                    i--
                }
                cursorCol = newCol
            }

            '?' -> continueSequence(ESC_CSI_QUESTIONMARK) // Private mode set.
            '>' -> continueSequence(ESC_CSI_BIGGERTHAN)

            '`' -> setCursorColRespectingOriginMode(getArg0(1) - 1) // HPA.

            'b' -> { // REP, repeat the previous graphic character.
                if (lastEmittedCodePoint != -1) {
                    repeat(getArg0(1)) { emitCodePoint(lastEmittedCodePoint) }
                }
            }

            'c' ->
                // DA1, primary device attributes. The first parameter names the service
                // class - 64 for vt420 - and is the one anything still reads.
                if (getArg0(0) == 0) session.write("\u001B[?64;1;2;6;9;15;18;21;22c")

            'd' -> setCursorRow(min(max(1, getArg0(1)), rows) - 1) // VPA.

            'e' -> setCursorPosition(cursorCol, cursorRow + getArg0(1)) // VPR.

            'g' -> // Clear tab stop.
                when (getArg0(0)) {
                    0 -> tabStop[cursorCol] = false
                    3 -> tabStop.fill(false)
                    else -> Unit // Specified to have no effect.
                }

            'h' -> doSetMode(true)
            'l' -> doSetMode(false)

            'm' -> selectGraphicRendition() // Up to 16 numeric arguments.

            'n' -> // ECMA-48 status report commands.
                when (getArg0(0)) {
                    5 -> // DSR: the answer is ESC [ 0 n, "terminal OK".
                        session.write(byteArrayOf(27, '['.code.toByte(), '0'.code.toByte(), 'n'.code.toByte()), 0, 4)
                    6 -> // CPR: ESC [ y ; x R with the cursor position.
                        session.write(String.format(Locale.US, "\u001B[%d;%dR", cursorRow + 1, cursorCol + 1))
                    else -> Unit
                }

            'r' -> {
                // DECSTBM, the scrolling region. The sequence numbers the top line 1..23
                // while we number it 0..22, and numbers the bottom line the same way we do
                // (we store the first line *below* the region), so only the top is adjusted.
                // top + 2 <= bottom is required.
                topMargin = max(0, min(getArg0(1) - 1, rows - 2))
                bottomMargin = max(topMargin + 2, min(getArg1(rows), rows))
                // DECSTBM then moves the cursor to column 1, line 1, respecting origin mode.
                setCursorPosition(0, 0)
            }

            's' ->
                if (isDecSetBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                    // DECSLRM, the left and right margins.
                    leftMargin = min(getArg0(1) - 1, columns - 2)
                    rightMargin = max(leftMargin + 1, min(getArg1(columns), columns))
                    // DECSLRM moves the cursor to column 1, line 1.
                    setCursorPosition(0, 0)
                } else {
                    // ANSI.SYS save cursor, only available while DECLRMM is off.
                    saveCursor()
                }

            't' -> // Window manipulation, from dtterm and its extensions.
                when (getArg0(0)) {
                    11 -> session.write("\u001B[1t") // Window state.
                    13 -> session.write("\u001B[3;0;0t") // Window position.
                    // Window size in pixels; reported as characters times 12.
                    14 -> session.write(String.format(Locale.US, "\u001B[4;%d;%dt", rows * 12, columns * 12))
                    // Text area size in characters.
                    18 -> session.write(String.format(Locale.US, "\u001B[8;%d;%dt", rows, columns))
                    // Screen size in characters: the same, since the view is not resizable
                    // from the shell.
                    19 -> session.write(String.format(Locale.US, "\u001B[9;%d;%dt", rows, columns))
                    // Icon label and title reports are disabled for security reasons.
                    20 -> session.write("\u001B]LIconLabel\u001B\\")
                    21 -> session.write("\u001B]l\u001B\\")
                    22 -> { // Push the title on the stack (0 icon+window, 1 icon, 2 window).
                        titleStack.push(titleOrNull)
                        if (titleStack.size > 20) titleStack.removeAt(0)
                    }
                    23 -> if (titleStack.isNotEmpty()) setTitle(titleStack.pop()) // Pop it.
                    else -> Unit
                }

            'u' -> restoreCursor() // ANSI.SYS restore cursor.

            ' ' -> continueSequence(ESC_CSI_ARGS_SPACE)

            else -> parseArg(b)
        }
    }

    /** "CSI Pm $ b": the rectangular-area operations. */
    private fun doCsiDollar(b: Int) {
        val originMode = isDecSetBitSet(DECSET_BIT_ORIGIN_MODE)
        val effectiveTopMargin = if (originMode) topMargin else 0
        val effectiveBottomMargin = if (originMode) bottomMargin else rows
        val effectiveLeftMargin = if (originMode) leftMargin else 0
        val effectiveRightMargin = if (originMode) rightMargin else columns

        when (b.toChar()) {
            'v' -> {
                // DECCRA, copy a rectangular area: the coordinates follow origin mode, the
                // copy takes on the line attributes of the destination, values past the page
                // edge are clamped to it, off-page destinations are clipped, and the cursor
                // does not move.
                val topSource = min(getArg(0, 1, true) - 1 + effectiveTopMargin, rows)
                val leftSource = min(getArg(1, 1, true) - 1 + effectiveLeftMargin, columns)
                // Inclusive, so no -1 here:
                val bottomSource = min(max(getArg(2, rows, true) + effectiveTopMargin, topSource), rows)
                val rightSource = min(max(getArg(3, columns, true) + effectiveLeftMargin, leftSource), columns)
                val destinationTop = min(getArg(5, 1, true) - 1 + effectiveTopMargin, rows)
                val destinationLeft = min(getArg(6, 1, true) - 1 + effectiveLeftMargin, columns)
                val heightToCopy = min(rows - destinationTop, bottomSource - topSource)
                val widthToCopy = min(columns - destinationLeft, rightSource - leftSource)
                screen.blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destinationTop)
            }

            // DECSERA (selective erase), DECFRA (fill) and DECERA (erase).
            '{', 'x', 'z' -> {
                val erase = b != 'x'.code
                val selective = b == '{'.code
                // Only DECSERA keeps the visual attributes.
                val keepVisualAttributes = erase && selective
                var position = 0
                val fillChar = if (erase) ' '.code else getArg(position++, -1, true)
                // "Pch can be any value from 32 to 126 or from 160 to 255. If Pch is not in
                // this range, then the terminal ignores the DECFRA command".
                if (fillChar in 32..126 || fillChar in 160..255) {
                    // Values past the page edge are clamped to it.
                    val top = min(getArg(position++, 1, true) + effectiveTopMargin, effectiveBottomMargin + 1)
                    val left = min(getArg(position++, 1, true) + effectiveLeftMargin, effectiveRightMargin + 1)
                    val bottom = min(getArg(position++, rows, true) + effectiveTopMargin, effectiveBottomMargin)
                    val right = min(getArg(position, columns, true) + effectiveLeftMargin, effectiveRightMargin)
                    val style = currentStyle
                    for (row in top - 1 until bottom) {
                        for (col in left - 1 until right) {
                            val cellIsProtected =
                                TextStyle.decodeEffect(screen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED != 0
                            if (!selective || !cellIsProtected) {
                                screen.setChar(col, row, fillChar, if (keepVisualAttributes) screen.getStyleAt(row, col) else style)
                            }
                        }
                    }
                }
            }

            // DECCARA (change) and DECRARA (reverse) attributes in a rectangle.
            'r', 't' -> {
                val reverse = b == 't'.code
                // FIXME: "coordinates of the rectangular area are affected by the setting of
                // origin mode (DECOM)".
                val top = min(getArg(0, 1, true) - 1, effectiveBottomMargin) + effectiveTopMargin
                val left = min(getArg(1, 1, true) - 1, effectiveRightMargin) + effectiveLeftMargin
                val bottom = min(getArg(2, rows, true) + 1, effectiveBottomMargin - 1) + effectiveTopMargin
                val right = min(getArg(3, columns, true) + 1, effectiveRightMargin - 1) + effectiveLeftMargin
                if (argIndex >= 4) {
                    if (argIndex >= args.size) argIndex = args.size - 1
                    for (i in 4..argIndex) {
                        var bits = 0
                        var setOrClear = true // True sets, false clears.
                        when (getArg(i, 0, false)) {
                            // Attributes off: no bold, no underline, no blink, positive image.
                            0 -> {
                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD or
                                    TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or
                                    TextStyle.CHARACTER_ATTRIBUTE_BLINK or
                                    TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                if (!reverse) setOrClear = false
                            }
                            1 -> bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                            4 -> bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                            5 -> bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                            7 -> bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                            22 -> {
                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                                setOrClear = false
                            }
                            24 -> {
                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                                setOrClear = false
                            }
                            25 -> {
                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                setOrClear = false
                            }
                            27 -> {
                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                setOrClear = false
                            }
                        }
                        // DECRARA ignores the bits outside (1, 4, 5, 7).
                        if (!reverse || setOrClear) {
                            screen.setOrClearEffect(
                                bits,
                                setOrClear,
                                reverse,
                                isDecSetBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                                effectiveLeftMargin,
                                effectiveRightMargin,
                                top,
                                left,
                                bottom,
                                right,
                            )
                        }
                    }
                }
            }

            else -> unknownSequence(b)
        }
    }

    /** "CSI Pm " b": DECSCA, which characters a selective erase may remove. */
    private fun doCsiDoubleQuote(b: Int) {
        if (b != 'q'.code) {
            unknownSequence(b)
            return
        }
        when (getArg0(0)) {
            0, 2 -> // DECSED and DECSEL may erase them.
                effect = effect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED.inv()
            1 -> // DECSED and DECSEL may not.
                effect = effect or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
            else -> unknownSequence(b)
        }
    }

    /** "CSI Pm ' b": DECIC and DECDC, inserting and deleting whole columns. */
    private fun doCsiSingleQuote(b: Int) {
        when (b.toChar()) {
            '}' -> { // DECIC.
                val columnsAfterCursor = rightMargin - cursorCol
                val columnsToInsert = min(getArg0(1), columnsAfterCursor)
                val columnsToMove = columnsAfterCursor - columnsToInsert
                screen.blockCopy(cursorCol, 0, columnsToMove, rows, cursorCol + columnsToInsert, 0)
                blockClear(cursorCol, 0, columnsToInsert, rows)
            }
            '~' -> { // DECDC.
                val columnsAfterCursor = rightMargin - cursorCol
                val columnsToDelete = min(getArg0(1), columnsAfterCursor)
                val columnsToMove = columnsAfterCursor - columnsToDelete
                screen.blockCopy(cursorCol + columnsToDelete, 0, columnsToMove, rows, cursorCol, 0)
                blockClear(cursorRow + columnsToMove, 0, columnsToDelete, rows)
            }
            else -> unknownSequence(b)
        }
    }

    /** "CSI Pm $ y": DECRQM, asking whether a private mode is set. */
    private fun doCsiQuestionMarkArgDollar(b: Int) {
        if (b != 'p'.code) {
            unknownSequence(b)
            return
        }
        val mode = getArg0(0)
        val value = if (mode == 47 || mode == 1047 || mode == 1049) {
            // That state is carried by the screen pointer rather than by a flag.
            if (screen === altBuffer) 1 else 2
        } else {
            when (val internalBit = mapDecSetBitToInternalBit(mode)) {
                -1 -> {
                    client?.logError(LOG_TAG, "Got DECRQM for unrecognized private DEC mode=$mode")
                    0 // 0 not recognized, 3 permanently set, 4 permanently reset.
                }
                else -> if (isDecSetBitSet(internalBit)) 1 else 2 // 1 set, 2 reset.
            }
        }
        session.write(String.format(Locale.US, "\u001B[?%d;%d${'$'}y", mode, value))
    }

    /** "CSI Pm SP b": DECSCUSR sets the cursor shape; the volume requests are ignored. */
    private fun doCsiArgsSpace(b: Int) {
        val arg = getArg0(0)
        when (b.toChar()) {
            'q' -> { // DECSCUSR: http://www.vt100.net/docs/vt510-rm/DECSCUSR.
                when (arg) {
                    0, 1, 2 -> cursorStyle = TERMINAL_CURSOR_STYLE_BLOCK // Blinking or steady block.
                    3, 4 -> cursorStyle = TERMINAL_CURSOR_STYLE_UNDERLINE // Blinking or steady underline.
                    5, 6 -> cursorStyle = TERMINAL_CURSOR_STYLE_BAR // Blinking or steady bar (xterm).
                }
            }
            't', 'u' -> Unit // Set margin-bell volume: ignore.
            else -> unknownSequence(b)
        }
    }

    /** Select Graphic Rendition - http://en.wikipedia.org/wiki/ANSI_escape_code#graphics. */
    private fun selectGraphicRendition() {
        if (argIndex >= args.size) argIndex = args.size - 1
        var i = 0
        while (i <= argIndex) {
            var code = args[i]
            if (code < 0) {
                if (argIndex > 0) {
                    i++
                    continue
                }
                code = 0
            }
            when {
                code == 0 -> { // Reset everything.
                    foreColor = TextStyle.COLOR_INDEX_FOREGROUND
                    backColor = TextStyle.COLOR_INDEX_BACKGROUND
                    effect = 0
                }
                code == 1 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_BOLD
                code == 2 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_DIM
                code == 3 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_ITALIC
                code == 4 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                code == 5 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_BLINK
                code == 7 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                code == 8 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE
                code == 9 -> effect = effect or TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
                // Entering and leaving the alt charset (TERM=linux) is not modelled.
                code == 10 || code == 11 -> Unit
                code == 22 -> effect = effect and
                    (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_DIM).inv()
                code == 23 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC.inv()
                code == 24 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                code == 25 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_BLINK.inv()
                code == 27 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE.inv()
                code == 28 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE.inv()
                code == 29 -> effect = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH.inv()
                code in 30..37 -> foreColor = code - 30
                code == 38 || code == 48 -> {
                    // Extended colour: "38;2;R;G;B" for 24-bit, "38;5;INDEX" for the palette,
                    // and the same two forms for 48 (background).
                    if (i + 2 > argIndex) {
                        i++
                        continue
                    }
                    when (args[i + 1]) {
                        2 ->
                            if (i + 4 > argIndex) {
                                client?.logWarn(LOG_TAG, "Too few CSI$code;2 RGB arguments")
                            } else {
                                val red = args[i + 2]
                                val green = args[i + 3]
                                val blue = args[i + 4]
                                if (red < 0 || green < 0 || blue < 0 || red > 255 || green > 255 || blue > 255) {
                                    finishSequenceAndLogError("Invalid RGB: $red,$green,$blue")
                                } else {
                                    val argbColor = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
                                    if (code == 38) foreColor = argbColor else backColor = argbColor
                                }
                                i += 4 // "2;Pr;Pg;Pb"
                            }
                        5 -> {
                            val color = args[i + 2]
                            i += 2 // "5;Ps"
                            if (color in 0 until TextStyle.NUM_INDEXED_COLORS) {
                                if (code == 38) foreColor = color else backColor = color
                            } else if (LOG_ESCAPE_SEQUENCES) {
                                client?.logWarn(LOG_TAG, "Invalid color index: $color")
                            }
                        }
                        else -> finishSequenceAndLogError("Invalid ISO-8613-3 SGR first argument: ${args[i + 1]}")
                    }
                }
                code == 39 -> foreColor = TextStyle.COLOR_INDEX_FOREGROUND // Default foreground.
                code in 40..47 -> backColor = code - 40
                code == 49 -> backColor = TextStyle.COLOR_INDEX_BACKGROUND // Default background.
                code in 90..97 -> foreColor = code - 90 + 8 // Bright foreground, aixterm codes.
                code in 100..107 -> backColor = code - 100 + 8 // Bright background, aixterm.
                else -> if (LOG_ESCAPE_SEQUENCES) {
                    client?.logWarn(LOG_TAG, String.format(Locale.ROOT, "SGR unknown code %d", code))
                }
            }
            i++
        }
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> doOscSetTextParameters("\u0007") // BEL terminates the string.
            27 -> continueSequence(ESC_OSC_ESC) // ESC may be the start of the terminator.
            else -> collectOSCArgs(b)
        }
    }

    private fun doOscEsc(b: Int) {
        when (b.toChar()) {
            '\\' -> doOscSetTextParameters("\u001B\\")
            else -> {
                // The ESC was not followed by a backslash, so both characters belong in the
                // string after all.
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(ESC_OSC)
            }
        }
    }

    /** OSC set-text parameters, reached from BEL or from ST. */
    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        var value = -1
        var textParameter = ""
        // Read the leading "$value" of the initial "$value;..." part.
        var position = 0
        while (position < oscOrDeviceControlArgs.length) {
            val b = oscOrDeviceControlArgs[position].code
            if (b == ';'.code) {
                textParameter = oscOrDeviceControlArgs.substring(position + 1)
                break
            } else if (b in '0'.code..'9'.code) {
                value = (if (value < 0) 0 else value * 10) + (b - '0'.code)
            } else {
                unknownSequence(b)
                return
            }
            position++
        }

        when (value) {
            0, 1, 2 -> setTitle(textParameter) // Icon name and/or window title.

            4 -> {
                // "Ps = 4 ; c ; spec" repaints colour number c from an XParseColor spec, and
                // any number of c/spec pairs may follow. A "?" instead of a spec makes us
                // answer with the same form, so the caller can read the colour back.
                var colorIndex = -1
                var pairStart = -1
                var i = 0
                while (true) {
                    val endOfInput = i == textParameter.length
                    val b = if (endOfInput) ';'.code else textParameter[i].code
                    if (b == ';'.code) {
                        if (pairStart < 0) {
                            pairStart = i + 1
                        } else {
                            if (colorIndex < 0 || colorIndex > 255) {
                                unknownSequence(b)
                                return
                            }
                            colors.tryParseColor(colorIndex, textParameter.substring(pairStart, i))
                            session.onColorsChanged()
                            colorIndex = -1
                            pairStart = -1
                        }
                    } else if (pairStart >= 0) {
                        // Inside the colour spec itself.
                    } else if (b in '0'.code..'9'.code) {
                        colorIndex = (if (colorIndex < 0) 0 else colorIndex * 10) + (b - '0'.code)
                    } else {
                        unknownSequence(b)
                        return
                    }
                    if (endOfInput) break
                    i++
                }
            }

            10, 11, 12 -> {
                // Set the foreground, background or cursor colour; more than one spec may
                // follow, each applying to the next slot.
                var specialIndex = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10)
                var specStart = 0
                var charIndex = 0
                while (true) {
                    val endOfInput = charIndex == textParameter.length
                    if (endOfInput || textParameter[charIndex] == ';') {
                        try {
                            val colorSpec = textParameter.substring(specStart, charIndex)
                            if (colorSpec == "?") {
                                // Report the colour the way xterm and gnome-terminal do.
                                val rgb = colors.currentColors[specialIndex]
                                val red = 65535 * ((rgb and 0x00FF0000) shr 16) / 255
                                val green = 65535 * ((rgb and 0x0000FF00) shr 8) / 255
                                val blue = 65535 * (rgb and 0x000000FF) / 255
                                session.write(
                                    "\u001B]$value;rgb:" +
                                        String.format(Locale.US, "%04x", red) + "/" +
                                        String.format(Locale.US, "%04x", green) + "/" +
                                        String.format(Locale.US, "%04x", blue) + bellOrStringTerminator,
                                )
                            } else {
                                colors.tryParseColor(specialIndex, colorSpec)
                                session.onColorsChanged()
                            }
                            specialIndex++
                            val done = if (endOfInput || specialIndex > TextStyle.COLOR_INDEX_CURSOR) {
                                true
                            } else {
                                charIndex++
                                charIndex >= textParameter.length
                            }
                            if (done) break
                            specStart = charIndex
                        } catch (ignored: NumberFormatException) {
                            // Ignore.
                        }
                    }
                    charIndex++
                }
            }

            52 -> { // Manipulate selection data: skip the optional selection letters.
                val startIndex = textParameter.indexOf(";") + 1
                try {
                    val clipboardText = String(Base64.decode(textParameter.substring(startIndex), 0), StandardCharsets.UTF_8)
                    session.onCopyTextToClipboard(clipboardText)
                } catch (e: Exception) {
                    client?.logError(LOG_TAG, "OSC Manipulate selection, invalid string '$textParameter")
                }
            }

            104 ->
                // "104;$c" puts colour $c back to its default; with no parameters the whole
                // table goes back.
                if (textParameter.isEmpty()) {
                    colors.reset()
                    session.onColorsChanged()
                } else {
                    var index = 0
                    var charIndex = 0
                    while (true) {
                        val endOfInput = charIndex == textParameter.length
                        if (endOfInput || textParameter[charIndex] == ';') {
                            try {
                                colors.reset(textParameter.substring(index, charIndex).toInt())
                                session.onColorsChanged()
                                if (endOfInput) break
                                charIndex++
                                index = charIndex
                            } catch (ignored: NumberFormatException) {
                                // Ignore.
                            }
                        }
                        charIndex++
                    }
                }

            110, 111, 112 -> { // Reset the foreground, background or cursor colour.
                colors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110))
                session.onColorsChanged()
            }

            119 -> Unit // Reset highlight colour.

            else -> unknownParameter(value)
        }
        finishSequence()
    }

    private fun blockClear(sx: Int, sy: Int, w: Int) {
        blockClear(sx, sy, w, 1)
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int) {
        screen.blockSet(sx, sy, w, h, ' '.code, currentStyle)
    }

    /** "CSI P_m h" sets and "CSI P_m l" resets an ANSI mode. */
    private fun doSetMode(newValue: Boolean) {
        when (getArg0(0)) {
            4 -> insertMode = newValue // IRM: insert vs replace mode.
            20 -> unknownParameter(getArg0(0)) // LNM, normal linefeed: http://www.vt100.net/docs/vt510-rm/LNM
            34 -> Unit // Normal cursor visibility with TERM=screen.
            else -> unknownParameter(getArg0(0))
        }
    }

    /**
     * Move the cursor honouring [DECSET_BIT_ORIGIN_MODE]. Use [setCursorRowCol] for
     * absolute positions.
     */
    private fun setCursorPosition(x: Int, y: Int) {
        val originMode = isDecSetBitSet(DECSET_BIT_ORIGIN_MODE)
        val effectiveTopMargin = if (originMode) topMargin else 0
        val effectiveBottomMargin = if (originMode) bottomMargin else rows
        val effectiveLeftMargin = if (originMode) leftMargin else 0
        val effectiveRightMargin = if (originMode) rightMargin else columns
        val newRow = max(effectiveTopMargin, min(effectiveTopMargin + y, effectiveBottomMargin - 1))
        val newCol = max(effectiveLeftMargin, min(effectiveLeftMargin + x, effectiveRightMargin - 1))
        setCursorRowCol(newRow, newCol)
    }

    private fun scrollDownOneLine() {
        scrollCounter++
        if (leftMargin != 0 || rightMargin != columns) {
            // With horizontal margins nothing goes into the history; only the part of the
            // screen between the margins moves up.
            screen.blockCopy(leftMargin, topMargin + 1, rightMargin - leftMargin, bottomMargin - topMargin - 1, leftMargin, topMargin)
            // .. and the bottom row between the margins is blanked.
            screen.blockSet(leftMargin, bottomMargin - 1, rightMargin - leftMargin, 1, ' '.code, effect.toLong())
        } else {
            screen.scrollDownOneLine(topMargin, bottomMargin, currentStyle)
        }
    }

    /** Consume the next ASCII character of a parameter. */
    private fun parseArg(b: Int) {
        if (b in '0'.code..'9'.code) {
            if (argIndex < args.size) {
                val oldValue = args[argIndex]
                args[argIndex] = if (oldValue >= 0) oldValue * 10 + (b - '0'.code) else b - '0'.code
            }
            continueSequence(escapeState)
        } else if (b == ';'.code) {
            if (argIndex < args.size) argIndex++
            continueSequence(escapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun getArg0(defaultValue: Int): Int = getArg(0, defaultValue, true)

    private fun getArg1(defaultValue: Int): Int = getArg(1, defaultValue, true)

    private fun getArg(index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
        val stored = args[index]
        return if (stored < 0 || (stored == 0 && treatZeroAsDefault)) defaultValue else stored
    }

    private fun collectOSCArgs(b: Int) {
        if (oscOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            oscOrDeviceControlArgs.appendCodePoint(b)
            continueSequence(escapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun unimplementedSequence(b: Int) {
        logError("Unimplemented sequence char '${b.toChar()}' (U+" + String.format("%04x", b) + ")")
        finishSequence()
    }

    private fun unknownSequence(b: Int) {
        logError("Unknown sequence char '${b.toChar()}' (numeric value=$b)")
        finishSequence()
    }

    private fun unknownParameter(parameter: Int) {
        logError("Unknown parameter: $parameter")
        finishSequence()
    }

    private fun logError(errorType: String) {
        if (!LOG_ESCAPE_SEQUENCES) return
        val buf = StringBuilder()
        buf.append(errorType).append(", escapeState=").append(escapeState)
        var firstArg = true
        if (argIndex >= args.size) argIndex = args.size - 1
        for (i in 0..argIndex) {
            val value = args[i]
            if (value >= 0) {
                if (firstArg) {
                    firstArg = false
                    buf.append(", args={")
                } else {
                    buf.append(',')
                }
                buf.append(value)
            }
        }
        if (!firstArg) buf.append('}')
        finishSequenceAndLogError(buf.toString())
    }

    private fun finishSequenceAndLogError(error: String) {
        if (LOG_ESCAPE_SEQUENCES) client?.logWarn(LOG_TAG, error)
        finishSequence()
    }

    private fun finishSequence() {
        escapeState = ESC_NONE
    }

    /** Put one Unicode code point on the screen and advance the cursor. */
    private fun emitCodePoint(codePointArg: Int) {
        var codePoint = codePointArg
        lastEmittedCodePoint = codePoint
        if (if (useLineDrawingUsesG0) useLineDrawingG0 else useLineDrawingG1) {
            // The DEC special graphics character set:
            // http://www.vt100.net/docs/vt102-ug/table5-15.html.
            codePoint = LINE_DRAWING_G1[codePoint.toChar()]?.code ?: codePoint
        }

        val autoWrap = isDecSetBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(codePoint)
        val cursorInLastColumn = cursorCol == rightMargin - 1

        if (autoWrap) {
            if (cursorInLastColumn && ((aboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                screen.setLineWrap(cursorRow)
                cursorCol = leftMargin
                if (cursorRow + 1 < bottomMargin) {
                    cursorRow++
                } else {
                    scrollDownOneLine()
                }
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            // Outputting a wide character in the last column with autowrap off has no
            // obvious right answer; we ignore it.
            return
        }

        if (insertMode && displayWidth > 0) {
            // Push the text to the right out of the way.
            val destCol = cursorCol + displayWidth
            if (destCol < rightMargin) {
                screen.blockCopy(cursorCol, cursorRow, rightMargin - destCol, 1, destCol, cursorRow)
            }
        }

        // A combining character lands on the cell it belongs to rather than on the next one.
        val offsetDueToCombiningChar = if (displayWidth <= 0 && cursorCol > 0 && !aboutToAutoWrap) 1 else 0
        screen.setChar(cursorCol - offsetDueToCombiningChar, cursorRow, codePoint, currentStyle)

        if (autoWrap && displayWidth > 0) {
            aboutToAutoWrap = cursorCol == rightMargin - displayWidth
        }

        cursorCol = min(cursorCol + displayWidth, rightMargin - 1)
    }

    private fun setCursorRow(row: Int) {
        cursorRow = row
        aboutToAutoWrap = false
    }

    private fun setCursorCol(col: Int) {
        cursorCol = col
        aboutToAutoWrap = false
    }

    /** Move the cursor, but stay inside the margins when [DECSET_BIT_ORIGIN_MODE] is on. */
    private fun setCursorColRespectingOriginMode(col: Int) {
        setCursorPosition(col, cursorRow)
    }

    /** Absolute cursor position, unlike [setCursorPosition] which honours origin mode. */
    private fun setCursorRowCol(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, columns - 1)
        aboutToAutoWrap = false
    }

    fun clearScrollCounter() {
        scrollCounter = 0
    }

    /** Put the terminal back into a state the user can work with, whatever it was in. */
    fun reset() {
        refreshCursorStyle()
        argIndex = 0
        sequenceShouldContinue = false
        escapeState = ESC_NONE
        insertMode = false
        topMargin = 0
        leftMargin = 0
        bottomMargin = rows
        rightMargin = columns
        aboutToAutoWrap = false

        foreColor = TextStyle.COLOR_INDEX_FOREGROUND
        backColor = TextStyle.COLOR_INDEX_BACKGROUND
        savedStateMain.savedForeColor = foreColor
        savedStateAlt.savedForeColor = foreColor
        savedStateMain.savedBackColor = backColor
        savedStateAlt.savedBackColor = backColor
        setDefaultTabStops()

        useLineDrawingG0 = false
        useLineDrawingG1 = false
        useLineDrawingUsesG0 = true

        savedStateMain.savedCursorRow = 0
        savedStateMain.savedCursorCol = 0
        savedStateMain.savedEffect = 0
        savedStateMain.savedDecFlags = 0
        savedStateAlt.savedCursorRow = 0
        savedStateAlt.savedCursorCol = 0
        savedStateAlt.savedEffect = 0
        savedStateAlt.savedDecFlags = 0
        currentDecSetFlags = 0
        // Wrapping is not what a real terminal boots with, but it makes the screen usable,
        // especially a small one.
        setDecSetBit(DECSET_BIT_AUTOWRAP, true)
        setDecSetBit(DECSET_BIT_CURSOR_ENABLED, true)
        savedDecSetFlags = currentDecSetFlags
        savedStateMain.savedDecFlags = currentDecSetFlags
        savedStateAlt.savedDecFlags = currentDecSetFlags

        utf8Index = 0
        utf8ToFollow = 0

        colors.reset()
        session.onColorsChanged()
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String = screen.getSelectedText(x1, y1, x2, y2)

    /** The session title, or null when the remote never set one. */
    val title: String? get() = titleOrNull

    private fun setTitle(newTitle: String?) {
        val oldTitle = titleOrNull
        titleOrNull = newTitle
        if (oldTitle != newTitle) {
            session.titleChanged(oldTitle, newTitle)
        }
    }

    /** With DECSET 2004, paste is wrapped in "\u001B[200~" and "\u001B[201~". */
    fun paste(text: String) {
        // Always drop the escape character and the C1 controls first:
        var cleaned = text.replace(Regex("(\u001B|[\u0080-\u009F])"), "")
        // Then send bare carriage returns instead of line feeds:
        cleaned = cleaned.replace(Regex("\r?\n"), "\r")

        val bracketed = isDecSetBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
        if (bracketed) session.write("\u001B[200~")
        session.write(cleaned)
        if (bracketed) session.write("\u001B[201~")
    }

    /** The saved cursor, for DECSC and DECRC. See http://www.vt100.net/docs/vt510-rm/DECSC. */
    private class SavedScreenState {
        var savedCursorRow = 0
        var savedCursorCol = 0
        var savedEffect = 0
        var savedForeColor = 0
        var savedBackColor = 0
        var savedDecFlags = 0
        var useLineDrawingG0 = false
        var useLineDrawingG1 = false
        var useLineDrawingUsesG0 = true
    }

    override fun toString(): String =
        "TerminalEmulator[size=${screen.columns}x${screen.screenRows}, " +
            "margins={$topMargin,$rightMargin,$bottomMargin,$leftMargin}]"

    companion object {

        /** Set to true to log unknown or unimplemented escape sequences. */
        private const val LOG_ESCAPE_SEQUENCES = false

        const val MOUSE_LEFT_BUTTON = 0

        /** Motion while the left button is held. */
        const val MOUSE_LEFT_BUTTON_MOVED = 32
        const val MOUSE_WHEELUP_BUTTON = 64
        const val MOUSE_WHEELDOWN_BUTTON = 65

        /** What invalid data decodes to: http://en.wikipedia.org/wiki/Replacement_character */
        const val UNICODE_REPLACEMENT_CHAR = 0xFFFD

        /** Not inside an escape sequence. */
        private const val ESC_NONE = 0

        /** Saw ESC; continue in [doEsc]. */
        private const val ESC = 1

        /** Saw "ESC #". */
        private const val ESC_POUND = 2

        /** Saw "ESC (" - a G0 character-set designator follows. */
        private const val ESC_SELECT_LEFT_PAREN = 3

        /** Saw "ESC )" - a G1 character-set designator follows. */
        private const val ESC_SELECT_RIGHT_PAREN = 4

        /** "ESC [", the Control Sequence Introducer. */
        private const val ESC_CSI = 6

        /** "ESC [ ?". */
        private const val ESC_CSI_QUESTIONMARK = 7

        /** "ESC [ ${'$'}". */
        private const val ESC_CSI_DOLLAR = 8

        /** "ESC %". */
        private const val ESC_PERCENT = 9

        /** "ESC ]", an Operating System Control string. */
        private const val ESC_OSC = 10

        /** "ESC ] ESC", possibly the start of the string terminator. */
        private const val ESC_OSC_ESC = 11

        /** "ESC [ >". */
        private const val ESC_CSI_BIGGERTHAN = 12

        /** "ESC P", a Device Control String. */
        private const val ESC_P = 13

        /** "ESC [ ? Pm ${'$'}". */
        private const val ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14

        /** "ESC [ Pm SP". */
        private const val ESC_CSI_ARGS_SPACE = 15

        /** "ESC [ Pm *". */
        private const val ESC_CSI_ARGS_ASTERIX = 16

        /** 'ESC [ "' */
        private const val ESC_CSI_DOUBLE_QUOTE = 17

        /** "ESC [ '". */
        private const val ESC_CSI_SINGLE_QUOTE = 18

        /** "ESC [ !". */
        private const val ESC_CSI_EXCLAMATION = 19

        /** How many arguments one escape sequence may carry; the ANSI standard name is Ps. */
        private const val MAX_ESCAPE_PARAMETERS = 16

        /** Big enough to hold a reasonable OSC 52 paste. */
        private const val MAX_OSC_STRING_LENGTH = 8192

        /** DECSET 1 - application cursor keys. */
        private const val DECSET_BIT_APPLICATION_CURSOR_KEYS = 1

        private const val DECSET_BIT_REVERSE_VIDEO = 1 shl 1

        /**
         * DECSET 6 (DECOM): with origin mode the home cursor sits inside the margins, line
         * numbering starts at the top margin and the cursor cannot leave the margins. Without
         * it, home is the upper-left corner and the margins do not constrain the cursor.
         */
        private const val DECSET_BIT_ORIGIN_MODE = 1 shl 2

        /**
         * DECSET 7 (DECAWM): with autowrap, characters received on the right border go to the
         * start of the next line, scrolling if the cursor is at the end of the region.
         * Without it they replace what is already on the page.
         */
        private const val DECSET_BIT_AUTOWRAP = 1 shl 3

        /** DECSET 25 - whether the cursor is shown, see [isCursorEnabled]. */
        private const val DECSET_BIT_CURSOR_ENABLED = 1 shl 4
        private const val DECSET_BIT_APPLICATION_KEYPAD = 1 shl 5

        /** DECSET 1000 - report mouse press and release. */
        private const val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 shl 6

        /** DECSET 1002 - as 1000, plus motion while a button is held. */
        private const val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 shl 7

        /** DECSET 1004 - not implemented. */
        private const val DECSET_BIT_SEND_FOCUS_EVENTS = 1 shl 8

        /** DECSET 1006 - the SGR-like mouse protocol, which is the sane modern choice. */
        private const val DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 shl 9

        /** DECSET 2004 - see [paste]. */
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10

        /** Toggled by DECLRMM - http://www.vt100.net/docs/vt510-rm/DECLRMM */
        private const val DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 shl 11

        /** Not really a DECSET bit - http://www.vt100.net/docs/vt510-rm/DECSACE */
        private const val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 shl 12

        private const val DECSET_BIT_ALTERNATE_SCROLL = 1 shl 13

        /** How many parameter arguments one sequence may carry. */
        const val TERMINAL_TRANSCRIPT_ROWS_MIN = 100
        const val TERMINAL_TRANSCRIPT_ROWS_MAX = 50000
        const val DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000

        /** The supported cursor shapes. */
        const val TERMINAL_CURSOR_STYLE_BLOCK = 0
        const val TERMINAL_CURSOR_STYLE_UNDERLINE = 1
        const val TERMINAL_CURSOR_STYLE_BAR = 2
        const val DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK

        @JvmField
        val TERMINAL_CURSOR_STYLES_LIST = listOf(
            TERMINAL_CURSOR_STYLE_BLOCK,
            TERMINAL_CURSOR_STYLE_UNDERLINE,
            TERMINAL_CURSOR_STYLE_BAR,
        )

        private const val LOG_TAG = "TerminalEmulator"

        /** The DEC special graphics set, http://www.vt100.net/docs/vt102-ug/table5-15.html. */
        private val LINE_DRAWING_G1 = mapOf(
            '_' to ' ', // Blank.
            '`' to '◆', // Diamond.
            '0' to '█', // Solid block.
            'a' to '▒', // Checker board.
            'b' to '␉', // Horizontal tab.
            'c' to '␌', // Form feed.
            'd' to '\r', // Carriage return.
            'e' to '␊', // Linefeed.
            'f' to '°', // Degree.
            'g' to '±', // Plus-minus.
            'h' to '\n', // Newline.
            'i' to '␋', // Vertical tab.
            'j' to '┘', // Lower right corner.
            'k' to '┐', // Upper right corner.
            'l' to '┌', // Upper left corner.
            'm' to '└', // Lower left corner.
            'n' to '┼', // Crossing lines.
            'o' to '⎺', // Horizontal line - scan 1.
            'p' to '⎻', // Horizontal line - scan 3.
            'q' to '─', // Horizontal line - scan 5.
            'r' to '⎼', // Horizontal line - scan 7.
            's' to '⎽', // Horizontal line - scan 9.
            't' to '├', // T facing rightwards.
            'u' to '┤', // T facing leftwards.
            'v' to '┴', // T facing upwards.
            'w' to '┬', // T facing downwards.
            'x' to '│', // Vertical line.
            'y' to '≤', // Less than or equal to.
            'z' to '≥', // Greater than or equal to.
            '{' to 'π', // Pi.
            '|' to '≠', // Not equal to.
            '}' to '£', // UK pound.
            '~' to '·', // Centered dot.
        )

        /** Map a DECSET number onto our own flag bit, or -1 when we do not model it. */
        private fun mapDecSetBitToInternalBit(decsetBit: Int): Int =
            when (decsetBit) {
                1 -> DECSET_BIT_APPLICATION_CURSOR_KEYS
                5 -> DECSET_BIT_REVERSE_VIDEO
                6 -> DECSET_BIT_ORIGIN_MODE
                7 -> DECSET_BIT_AUTOWRAP
                25 -> DECSET_BIT_CURSOR_ENABLED
                66 -> DECSET_BIT_APPLICATION_KEYPAD
                69 -> DECSET_BIT_LEFTRIGHT_MARGIN_MODE
                1000 -> DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
                1002 -> DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
                1004 -> DECSET_BIT_SEND_FOCUS_EVENTS
                1006 -> DECSET_BIT_MOUSE_PROTOCOL_SGR
                2004 -> DECSET_BIT_BRACKETED_PASTE_MODE
                // xterm "alternate scroll": the remote wants wheel and touch scrolls turned
                // into arrow keys while it holds the alternate screen.
                1007 -> DECSET_BIT_ALTERNATE_SCROLL
                else -> -1
            }
    }
}
