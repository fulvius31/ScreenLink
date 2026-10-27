package com.sangiorgisrl.screenlink.terminal.view

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Scroller
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler
import com.sangiorgisrl.screenlink.terminal.emulator.TermSession
import com.sangiorgisrl.screenlink.terminal.emulator.TerminalEmulator
import com.sangiorgisrl.screenlink.terminal.view.textselection.TextSelectionCursorController
import com.sangiorgisrl.screenlink.ui.terminal.FlingBounds
import com.sangiorgisrl.screenlink.ui.terminal.ScreenCopyMode
import com.sangiorgisrl.screenlink.ui.terminal.TerminalScrollAction
import com.sangiorgisrl.screenlink.ui.terminal.TerminalScrollGesture
import com.sangiorgisrl.screenlink.ui.terminal.TerminalScrollRouter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The canvas that shows a [TermSession] and turns touches, keys and IME input back into
 * bytes for it.
 */
class TerminalView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** The session on screen right now; its emulator is [emulator]. */
    var termSession: TermSession? = null
        private set

    /** The emulator being displayed; null until the first layout gave us a size. */
    var emulator: TerminalEmulator? = null
        private set

    /** Font size and typeface used for painting; replaced whenever either changes. */
    var renderer: TerminalRenderer = TerminalRenderer(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            DEFAULT_FONT_SIZE_SP,
            context.resources.displayMetrics,
        ).roundToInt(),
        Typeface.MONOSPACE,
    )
        private set

    /** Who owns the session: answers configuration questions and receives key/IME events. */
    lateinit var client: TerminalViewClient

    private var textSelectionCursorController: TextSelectionCursorController? = null

    private var terminalCursorBlinkerHandler: Handler? = null
    private var terminalCursorBlinkerRunnable: CursorBlinker? = null
    private var terminalCursorBlinkerRate = 0

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    var topRow = 0
        set(value) {
            field = value
            // Every write goes through here so the listener can never drift from the screen.
            scrollPositionListener?.onScrollPositionChanged(field == 0)
        }

    /**
     * True while this view has put the remote GNU Screen window into its own "copy mode"
     * scrollback (see [ScreenCopyMode]). Screen keeps history on the SERVER, so this is
     * the only way to read output produced before - or while - this client attached.
     * Kept in sync by this class alone: every keystroke leaves copy mode first, because
     * screen exits copy mode on any unknown key.
     */
    private var copyModeActive = false

    /**
     * True when the attached session is a GNU Screen window, i.e. copy mode exists to be
     * driven at all. Only the client knows that (it owns the session), so it tells this
     * view at attach time through [screenCopyModeSupported].
     */
    var screenCopyModeSupported = false
        set(value) {
            field = value
            if (!value) copyModeRows = 0
        }

    /**
     * Rows this view has moved INTO screen's scrollback since copy mode opened
     * (0 = at the live prompt). Screen clamps its display at the prompt too, so when a
     * forward scroll brings this back to 0 the display is genuinely live again and copy
     * mode can be left on its own - which is what makes "drag back down to the prompt"
     * behave like every other Android list. -1 = lost count (a HOME/`g` jump).
     */
    private var copyModeRows = 0

    private val defaultSelectors = intArrayOf(-1, -1, -1, -1)

    private var scaleFactor = 1f

    /** Keep track of where the touch event we report as a mouse scroll started. */
    private var mouseScrollStartX = -1
    private var mouseScrollStartY = -1
    private var mouseStartDownTime = -1L

    private val scroller = Scroller(context)

    /** Pixels left over from the last drag that did not add up to a whole row. */
    private var scrollRemainder = 0f

    /** Left-over pixels of an in-flight momentum fling (see [onFling]). */
    private var flingRemainder = 0f

    /** If non-zero, the last unicode code point received, when it was a combining accent. */
    private var combiningAccent = 0

    private val accessibilityEnabled: Boolean

    /** True while a finger (rather than a mouse) is the source of the current gesture. */
    private var scrolledWithFinger = false

    private val gestureRecognizer: GestureAndScaleRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {

        override fun onUp(event: MotionEvent): Boolean {
            scrollRemainder = 0f
            val em = emulator
            if (em != null && em.isMouseTrackingActive &&
                !event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                !isSelectingText() && !scrolledWithFinger
            ) {
                // With mouse reporting on, act immediately - do not wait to see whether the
                // second tap of a double tap turns into a zoom.
                sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                return true
            }
            scrolledWithFinger = false
            return false
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (emulator == null) return true

            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            }
            requestFocus()
            client.onSingleTapUp(event)
            return true
        }

        override fun onScroll(e: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val em = emulator ?: return true
            if (em.isMouseTrackingActive && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                // Moving with the button held is pointer motion, not scrolling. We never
                // report that for touch, because a motion event without a preceding press
                // would be a protocol error, and we only send presses for mouse input.
                sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
            } else {
                scrolledWithFinger = true
                // Sign convention (see TerminalScrollGesture): a finger dragged DOWN
                // reports a NEGATIVE distanceY and must pull older content into view.
                val step = TerminalScrollGesture.dragStep(distanceY, renderer.fontLineSpacing.toFloat(), scrollRemainder)
                scrollRemainder = step.remainderPx
                doScroll(e, step.rowsBack)
            }
            return true
        }

        override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
            if (emulator == null || isSelectingText()) return true
            scaleFactor *= scale
            scaleFactor = client.onScale(scaleFactor)
            return true
        }

        override fun onFling(e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val em = emulator ?: return true
            // Do not start a second scroll while one is still running:
            if (!scroller.isFinished) return true

            val mouseTrackingAtStartOfFling = em.isMouseTrackingActive
            val screenCopyAtStartOfFling = TerminalScrollRouter.gestureUsesScreenCopy(
                copyModeActive,
                screenCopyModeSupported,
                mouseTrackingAtStartOfFling,
            )
            // The scroller axis is "pixels of scrollback travelled", positive = back in
            // time, so a downward flick keeps moving through history. (The old code negated
            // the velocity and started the scroller AT topRow with the bounds on the other
            // side of it, which made every fling finish immediately: momentum scrolling
            // simply did not exist.)
            val lineSpacing = renderer.fontLineSpacing
            val velocity = TerminalScrollGesture.flingVelocityPx(velocityY)
            val bounds = when {
                screenCopyAtStartOfFling -> {
                    // Screen's buffer length is not knowable from here, so a fling gets one
                    // screen's worth of room each way and the REMOTE clamps it. The forward
                    // limit is the one thing we do know: how far this view has actually moved
                    // into the scrollback. Bounding it is what stops a downward flick past the
                    // prompt from exiting copy mode and immediately re-opening it.
                    val half = (em.rows / 2) * lineSpacing
                    val forwardPx = if (copyModeRows < 0) half else copyModeRows * lineSpacing
                    FlingBounds(-forwardPx, half)
                }

                mouseTrackingAtStartOfFling -> {
                    val half = (em.rows / 2) * lineSpacing
                    FlingBounds(-half, half)
                }

                else -> TerminalScrollGesture.flingBounds(topRow, em.screen.activeTranscriptRows, lineSpacing)
            }
            scroller.fling(0, 0, 0, velocity, 0, 0, bounds.minPx, bounds.maxPx)
            // Each fling starts from a clean sub-row remainder, otherwise the leftover
            // pixels of the previous fling shift this one by a row.
            flingRemainder = 0f

            post(object : Runnable {
                private var lastY = 0

                override fun run() {
                    val current = emulator ?: return
                    if (mouseTrackingAtStartOfFling != current.isMouseTrackingActive) {
                        scroller.abortAnimation()
                        return
                    }
                    if (scroller.isFinished) return
                    val more = scroller.computeScrollOffset()
                    val newY = scroller.currY
                    // Quantise the animated offset into rows, carrying the remainder.
                    flingRemainder += (newY - lastY)
                    lastY = newY
                    val rows = (flingRemainder / lineSpacing).toInt()
                    flingRemainder -= rows * lineSpacing
                    if (rows != 0) doScroll(e2, rows)
                    if (more) post(this)
                }
            })

            return true
        }

        override fun onDown(x: Float, y: Float): Boolean {
            // Why is true not returned here?
            // https://developer.android.com/training/gestures/detector.html#detect-a-subset-of-supported-gestures
            // Returning true instead does not fix the errors logged when long pressing in
            // the terminal text area ("ViewDragHelper: Ignoring pointerId=0 because
            // ACTION_DOWN was not received for this pointer before ACTION_MOVE"); commenting
            // out the GestureDetector call in GestureAndScaleRecognizer#onTouchEvent() does,
            // so the trouble comes from the GestureDetector itself.
            return false
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            // Do not treat it as a confirmed single tap - it may be followed by a zoom.
            return false
        }

        override fun onLongPress(event: MotionEvent) {
            if (gestureRecognizer.isInProgress()) return
            if (client.onLongPress(event)) return
            if (!isSelectingText()) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startTextSelectionMode(event)
            }
        }
    })

    init {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        accessibilityEnabled = manager?.isEnabled ?: false
    }

    /**
     * Turn terminal key/IME logging on or off for the whole app.
     *
     * @param value The boolean value that defines the state.
     */
    companion object {
        private const val LOG_TAG = "TerminalView"

        /** Where scroll-routing decisions are logged, so `adb logcat -s ScreenLinkScroll` shows them. */
        private const val SCROLL_LOG_TAG = "ScreenLinkScroll"

        /** How long to wait for the soft-keyboard animation to settle before reflowing. */
        private const val RESIZE_DEBOUNCE_MS = 120L

        /** Font size used before the client applies the persisted one. */
        private const val DEFAULT_FONT_SIZE_SP = 14f

        /** Painted when there is no session yet. */
        private const val BACKGROUND_WITHOUT_SESSION = 0xFF000000.toInt()

        /** Vertical touch offset used when turning a handle position back into a row. */
        private const val CURSOR_Y_TOUCH_OFFSET = 40f

        /** Log terminal view key and IME events. */
        private var keyLoggingEnabled = false

        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000

        /**
         * Sets whether terminal view key logging is enabled or not.
         *
         * @param value The boolean value that defines the state.
         */
        @JvmStatic
        fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
            keyLoggingEnabled = value
        }
    }

    /**
     * @param client The [TerminalViewClient] implementation this view talks to.
     */
    fun setTerminalViewClient(client: TerminalViewClient) {
        this.client = client
    }

    /**
     * Attach a [TermSession] to this view.
     *
     * @return false when [session] was already the attached one.
     */
    fun attachSession(session: TermSession): Boolean {
        if (session === termSession) return false
        // Drop the previous session's repaint hook so a backgrounded tab's old view
        // is never retained (and can be garbage collected).
        termSession?.setOnOutputListener(null)
        updateTopRow(0)
        // A different session means a different (or no) screen window: our flag is
        // purely local, so it is simply dropped - no EXIT byte is written to the old one.
        copyModeActive = false
        copyModeRows = 0
        copyModeListener?.onScreenCopyModeChanged(false)

        termSession = session
        // Repaint as soon as new bytes arrive (coalesced per frame by Android), rather
        // than waiting for the passive 500ms cursor-blink invalidate.
        session.setOnOutputListener { postInvalidate() }
        emulator = null
        combiningAccent = 0

        updateSize()

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        isVerticalScrollBarEnabled = true

        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Only set inputType while this view is the view the keyboard is aimed at; if some
        // other view (an EditText) has focus, leaving it alone is what keeps that view working.
        outAttrs.inputType = when {
            !client.isTerminalViewSelected() ->
                // Corresponds to android:inputType="text"
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

            client.shouldEnforceCharBasedInput() -> {
                // Some keyboards do not reset their internal state on TYPE_NULL, which affects
                // some vendor-supplied keyboards (upstream issue #686 - the upstream project is
                // named in the package note). It is not a valid value per AOSP either, since no
                // InputType.TYPE_CLASS_* bit is set, and logs
                // "W/InputAttributes: Unexpected input class".
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }

            else -> {
                // TYPE_NULL is the most correct input type and avoids issues with other hacks.
                // Previous keyboard issues: upstream issues #25, #87, #126 and #137 (the last
                // one covers Japanese input with TYPE_NULL).
                InputType.TYPE_NULL
            }
        }

        // IME_ACTION_NONE cannot be used: it makes it impossible to enter newlines with the
        // on-screen keyboard on Android TV (see upstream issue #221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {

            override fun finishComposingText(): Boolean {
                logKeyEvents("IME: finishComposingText()")
                super.finishComposingText()

                editable?.let { content ->
                    sendTextToTerminal(content)
                    content.clear()
                }
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                logKeyEvents("IME: commitText(\"$text\", $newCursorPosition)")
                super.commitText(text, newCursorPosition)

                if (emulator == null) return true

                val content = editable ?: return true
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                logKeyEvents("IME: deleteSurroundingText($leftLength, $rightLength)")
                // Some software keyboards with spell checking enabled send leftLength > 1.
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                repeat(leftLength) { sendKeyEvent(deleteKey) }
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            private fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                val textLengthInChars = text.length
                var i = 0
                while (i < textLengthInChars) {
                    val firstChar = text[i]
                    var codePoint: Int
                    if (Character.isHighSurrogate(firstChar)) {
                        i++
                        codePoint = if (i < textLengthInChars) {
                            Character.toCodePoint(firstChar, text[i])
                        } else {
                            // End of string, high surrogate with no low surrogate after it:
                            TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                        }
                    } else {
                        codePoint = firstChar.code
                    }

                    // See onKeyDown(): shift is handled here for IME input, not via getUnicodeChar().
                    if (client.readShiftKey()) codePoint = Character.toUpperCase(codePoint)

                    var ctrlHeld = false
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n'.code) {
                            // The AOSP keyboard and its descendants send \n as text for the enter
                            // key rather than a key event. A terminal wants \r (with icrnl enabled
                            // it makes no difference - 'stty -icrnl' shows the real behaviour).
                            codePoint = '\r'.code
                        }

                        // E.g. the penti keyboard, which has a ctrl key.
                        ctrlHeld = true
                        codePoint = when (codePoint) {
                            31 -> '_'.code
                            30 -> '^'.code
                            29 -> ']'.code
                            28 -> '\\'.code
                            else -> codePoint + 96
                        }
                    }

                    inputCodePoint(codePoint, ctrlHeld, false)
                    i++
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int = emulator?.screen?.activeRows ?: 1

    override fun computeVerticalScrollExtent(): Int = emulator?.rows ?: 1

    override fun computeVerticalScrollOffset(): Int {
        val em = emulator ?: return 1
        return em.screen.activeRows + topRow - em.rows
    }

    fun onScreenUpdated() {
        val em = emulator ?: return

        val rowsInHistory = em.screen.activeTranscriptRows
        if (topRow < -rowsInHistory) updateTopRow(-rowsInHistory)

        if (isSelectingText()) {
            // Do not scroll while text is being selected: shift the selection instead so it
            // stays on the same characters.
            val rowShift = em.scrollCounter
            if (-topRow + rowShift > rowsInHistory) {
                // .. unless that would push it off the top of the transcript, in which case
                // selection is abandoned and we scroll to the live end.
                stopTextSelectionMode()
            } else {
                updateTopRow(topRow - rowShift)
                decrementYTextSelectionCursors(rowShift)
            }
        }

        // NOTE: new output deliberately does NOT yank the viewport back to the live prompt.
        // Doing that made finger/button scrolling useless - any output the remote produced
        // (a prompt redraw, a progress bar, a streaming assistant) instantly undid the
        // scroll, so it looked like the terminal ignored the gesture. Following stops while
        // the user is reading scrollback and resumes as soon as they return to the bottom,
        // which typing does by itself (see scrollToLive()).

        em.clearScrollCounter()

        invalidate()
        if (accessibilityEnabled) contentDescription = visibleText()
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in pixels.
     */
    fun setTextSize(textSize: Int) {
        renderer = TerminalRenderer(textSize, renderer.typeface)
        updateSize()
    }

    fun setTypeface(newTypeface: Typeface) {
        renderer = TerminalRenderer(renderer.textSize, newTypeface)
        updateSize()
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun isOpaque(): Boolean = true

    /**
     * The zero-indexed column and row of the terminal under [event].
     *
     * @param relativeToScroll when true, the scroll position counts, so with three rows
     *   scrolled away the top-left corner reports column 0, row -3; with false it is 0, 0.
     */
    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        var row = ((event.y - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing).toInt()
        val column = (event.x / renderer.fontWidth).toInt()
        if (relativeToScroll) {
            row += topRow
        }
        return intArrayOf(column, row)
    }

    /** Send a single mouse event code to the terminal. */
    private fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val em = emulator ?: return
        val columnAndRow = getColumnAndRow(e, false)
        var x = columnAndRow[0] + 1
        var y = columnAndRow[1] + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mouseStartDownTime == e.downTime) {
                x = mouseScrollStartX
                y = mouseScrollStartY
            } else {
                mouseStartDownTime = e.downTime
                mouseScrollStartX = x
                mouseScrollStartY = y
            }
        }
        em.sendMouseEvent(button, x, y, pressed)
    }

    /**
     * One line per CHANGE of the inputs the scroll router reads (drag, fling, wheel and the
     * Scroll buttons all pass through here). Scrolling is a routing decision, so when it
     * looks wrong on a device this is the whole answer: `adb logcat -s ScreenLinkScroll`
     * shows exactly which scrollback a gesture was sent to and why. Logged only when the
     * state changes, so a drag costs one line, not one per motion event.
     */
    private var lastScrollRouteState = ""

    private fun logScrollRoute(source: String, rowsBack: Int) {
        val em = emulator ?: return
        val state = "copyMode=$copyModeActive screenSession=$screenCopyModeSupported" +
            " mouseTracking=${em.isMouseTrackingActive} altBuffer=${em.isAlternateBufferActive}" +
            " altScroll=${em.isAlternateScrollActive} transcriptRows=${em.screen.activeTranscriptRows}" +
            " copyModeRows=$copyModeRows topRow=$topRow"
        if (state == lastScrollRouteState) return
        lastScrollRouteState = state
        Log.d(SCROLL_LOG_TAG, "$source rowsBack=$rowsBack $state")
    }

    /**
     * Perform a scroll, from a drag, a fling or a mouse wheel.
     *
     * @param rowsBack positive moves BACK in time (older content pulled into view),
     *                 negative moves forward toward the live prompt. This is the one
     *                 convention every gesture source is normalised to by
     *                 [TerminalScrollGesture].
     */
    private fun doScroll(event: MotionEvent?, rowsBack: Int) {
        val session = termSession ?: return
        val em = emulator ?: return
        logScrollRoute("gesture", rowsBack)
        if (TerminalScrollRouter.gestureUsesScreenCopy(copyModeActive, screenCopyModeSupported, em.isMouseTrackingActive) &&
            // Never OPEN copy mode to scroll toward the live prompt: at the prompt there is
            // nothing to come back from, and ENTER+ESC per event would flicker screen's
            // status line for the rest of the drag.
            (copyModeActive || rowsBack > 0)
        ) {
            // A GNU Screen window: screen's scrollback is the only one that reaches output
            // produced before this client attached, so the drag drives THAT (opening copy
            // mode on the first row that needs it). One write per gesture step keeps the
            // remote in sync with the finger.
            if (!copyModeActive) setScreenCopyMode(true)
            scrollScreenCopyMode(rowsBack)
            return
        }
        val back = rowsBack > 0
        val amount = kotlin.math.abs(rowsBack)
        val fromTouch = event != null && !event.isFromSource(InputDevice.SOURCE_MOUSE)
        repeat(amount) {
            when (TerminalScrollRouter.route(
                em.isMouseTrackingActive,
                em.isAlternateBufferActive,
                em.isAlternateScrollActive,
                fromTouch,
                back,
            )) {
                TerminalScrollAction.WHEEL_UP -> sendMouseEventCode(event!!, TerminalEmulator.MOUSE_WHEELUP_BUTTON, true)
                TerminalScrollAction.WHEEL_DOWN -> sendMouseEventCode(event!!, TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true)
                TerminalScrollAction.ARROW_UP -> handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0)
                TerminalScrollAction.ARROW_DOWN -> handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                TerminalScrollAction.SCROLL_BACK -> scrollTranscript(true)
                else -> scrollTranscript(false)
            }
        }
    }

    /** Scroll the terminal's own scrollback by one row (never emits input bytes). */
    private fun scrollTranscript(back: Boolean) {
        // The alt buffer keeps a transcript of its own, so touch scrolling still works inside it.
        updateTopRow(topRow + if (back) -1 else 1)
        if (!awakenScrollBars()) invalidate()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val em = emulator ?: return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            // Mouse wheel scrolling.
            doScroll(event, TerminalScrollGesture.wheelRows(event.getAxisValue(MotionEvent.AXIS_VSCROLL)))
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val em = emulator ?: return true

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event)
            gestureRecognizer.onTouchEvent(event)
            return true
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (event.action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipData = clipboard?.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val paste = clipData.getItemAt(0).coerceToText(context)
                    if (!TextUtils.isEmpty(paste)) {
                        exitScreenCopyModeIfNeeded()
                        em.paste(paste.toString())
                    }
                }
            } else if (em.isMouseTrackingActive) { // BUTTON_PRIMARY.
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.action == MotionEvent.ACTION_DOWN)

                    MotionEvent.ACTION_MOVE -> sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }

        gestureRecognizer.onTouchEvent(event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        logKeyEvents("onKeyPreIme(keyCode=$keyCode, event=$event)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            } else if (client.shouldBackButtonBeMappedToEscape()) {
                // Intercept the back button and treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (client.shouldUseCtrlSpaceWorkaround() && keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            // ctrl+space does not work on some ROMs without this workaround. However, this
            // breaks it on devices where it works out of the box.
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * Hardware key presses.
     *
     * Software keyboards generally do NOT come here - some do in some situations, so do not
     * rely on it. This is also called directly by the in-app keyboard bar, which builds a
     * [KeyEvent] on [KeyCharacterMap.VIRTUAL_KEYBOARD] (deviceId = -1) and would otherwise
     * need the mappings in `/system/usr/keychars/Virtual.kcm`.
     *
     * Shift for IME input is handled in `commitText()` with [Character.toUpperCase]; here it
     * goes through [KeyEvent.getUnicodeChar] instead, for both the hardware shift key (via
     * the effective meta state) and [TerminalViewClient.readShiftKey], following whatever the
     * active kcm file says. That makes the result keyboard- and language-dependent: the
     * default AOSP `Virtual.kcm` is English-only, so non-english layouts behave differently.
     * Two further kcm traps: ctrl and alt are not passed to `getUnicodeChar()`, so modified
     * values in a kcm file are ignored; and a kcm `fallback` such as
     * `key DPAD_UP { ... fn: fallback PAGE_UP }` arrives as a second, modifier-free event,
     * which is fine, but a kcm that maps a modifier to a raw control character (say
     * `fn: '\u0015'`) reaches the terminal as that character because [handleKeyCode] runs
     * before `getUnicodeChar()` and never saw the DPAD_LEFT keycode. Call
     * [KeyEvent.getUnicodeChar] before [handleKeyCode] if you rely on a custom kcm file
     * (upstream pull request #2237). Do not test fn/shift against a software keyboard that
     * calls `commitText()` instead of sending key events.
     *
     * Keymap background: https://source.android.com/devices/input/key-character-map-files
     * and https://source.android.com/devices/input/key-layout-files
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logKeyEvents("onKeyDown(keyCode=$keyCode, isSystem()=${event.isSystem}, event=$event)")
        if (emulator == null) return true
        if (isSelectingText()) {
            stopTextSelectionMode()
        }

        val session = termSession ?: return true

        if (client.onKeyDown(keyCode, event, session)) {
            invalidate()
            return true
        } else if (event.isSystem && (!client.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            session.write(event.characters)
            return true
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || client.readControlKey()
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || client.readAltKey()
        val shiftDown = event.isShiftPressed || client.readShiftKey()
        val rightAltDownFromEvent = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        // Upstream issue #731: a function-key press must not be handled twice.
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            logKeyEvents("handleKeyCode() took key event")
            return true
        }

        // Clear Ctrl since we handled it ourselves:
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (!rightAltDownFromEvent) {
            // Left alt goes to the terminal (e.g. Alt+B to jump back a word), so remove it.
            // Right alt / Alt Gr is left alone so it can still compose characters.
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState = effectiveMetaState or
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (client.readFnKey()) effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON

        var result = event.getUnicodeChar(effectiveMetaState)
        logKeyEvents("KeyEvent#getUnicodeChar($effectiveMetaState) returned: $result")
        if (result == 0) {
            return false
        }

        val oldCombiningAccent = combiningAccent
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // An accent typed earlier is written out now that its base character arrived:
            if (combiningAccent != 0) inputCodePoint(combiningAccent, controlDown, leftAltDown)
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (combiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(combiningAccent, result)
                if (combinedChar > 0) result = combinedChar
                combiningAccent = 0
            }
            inputCodePoint(result, controlDown, leftAltDown)
        }

        if (combiningAccent != oldCombiningAccent) invalidate()

        return true
    }

    fun inputCodePoint(codePointArg: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        logKeyEvents(
            "inputCodePoint(codePoint=$codePointArg, controlDownFromEvent=$controlDownFromEvent," +
                " leftAltDownFromEvent=$leftAltDownFromEvent)",
        )

        val session = termSession ?: return

        // Typing belongs to the shell, never to screen's pager: leave copy mode first.
        exitScreenCopyModeIfNeeded()

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        emulator?.setCursorBlinkState(true)

        // Same as handleKeyCode(): the in-app keyboard types through here, so a user who
        // had scrolled back through history must not keep looking at old output while
        // typing blind at the prompt.
        scrollToLive()

        var codePoint = codePointArg
        val controlDown = controlDownFromEvent || client.readControlKey()
        val altDown = leftAltDownFromEvent || client.readAltKey()

        if (client.onCodePoint(codePoint, controlDown, session)) return

        if (controlDown) {
            codePoint = when {
                codePoint in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                codePoint in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                codePoint == ' '.code || codePoint == '2'.code -> 0
                codePoint == '['.code || codePoint == '3'.code -> 27 // ^[ (Esc)
                codePoint == '\\'.code || codePoint == '4'.code -> 28
                codePoint == ']'.code || codePoint == '5'.code -> 29
                codePoint == '^'.code || codePoint == '6'.code -> 30 // control-^
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint == '_'.code || codePoint == '7'.code || codePoint == '/'.code -> 31
                codePoint == '8'.code -> 127 // DEL
                else -> codePoint
            }
        }

        if (codePoint > -1) {
            // Bluetooth keyboards sometimes send typographic lookalikes where a terminal
            // program wants plain ASCII; the chance someone wanted the fancy one is lower.
            codePoint = when (codePoint) {
                0x02DC -> 0x007E // SMALL TILDE -> TILDE (~)
                0x02CB -> 0x0060 // MODIFIER LETTER GRAVE ACCENT -> GRAVE ACCENT (`)
                0x02C6 -> 0x005E // MODIFIER LETTER CIRCUMFLEX ACCENT -> CIRCUMFLEX ACCENT (^)
                else -> codePoint
            }

            // With left alt held, an ESC goes first so Alt+B / Alt+F work in readline:
            session.writeCodePoint(altDown, codePoint)
        }
    }

    /** Input the specified keyCode if applicable and return whether the input was consumed. */
    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val session = termSession ?: return false
        if (copyModeActive) {
            val copyModeKey = ScreenCopyMode.keyForKeyCode(keyCode)
            if (copyModeKey != null) {
                if (keyCode == KeyEvent.KEYCODE_MOVE_END) {
                    // `G` is "bottom of the buffer" = the live prompt, so it is also the
                    // natural way OUT of copy mode: use it instead of sending it and then
                    // pretending we still know where the display is.
                    exitScreenCopyModeIfNeeded()
                    return true
                }
                // Page/arrow/home/end keys drive screen's scrollback while it is open.
                emulator?.setCursorBlinkState(true)
                session.write(copyModeKey)
                trackCopyModeKey(keyCode)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                // The user's ESC *is* the copy-mode exit - send it once, not twice.
                exitScreenCopyModeIfNeeded()
                return true
            }
            // Anything else (letters, ENTER, DEL...) is meant for the shell. Sending it raw
            // would also silently leave copy mode (screen(1): "all keys not described here
            // exit copy mode"), so leave it explicitly first and keep the app's idea of the
            // state honest.
            exitScreenCopyModeIfNeeded()
        }
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        emulator?.setCursorBlinkState(true)

        // Typing brings the viewport back to the live prompt if the user had scrolled back -
        // otherwise keystrokes land out of sight and it feels like scrolling broke.
        scrollToLive()

        val term = session.emulator ?: return false
        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            term.isCursorKeysApplicationMode,
            term.isKeypadApplicationMode,
        ) ?: return false
        session.write(code)
        return true
    }

    /**
     * Called when a key is released in the view.
     *
     * @return whether the event was handled.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        logKeyEvents("onKeyUp(keyCode=$keyCode, event=$event)")

        // Do not return early for KEYCODE_BACK and send it to the client: the user may be
        // trying to exit the activity.
        if (emulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (client.onKeyUp(keyCode, event)) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }

        return true
    }

    /**
     * Called during layout when the size of this view has changed. When just added to the
     * view hierarchy, the old values (0) are passed.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    /** Check whether the terminal size in rows and columns should be updated. */
    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        val session = termSession
        if (viewWidth == 0 || viewHeight == 0 || session == null) return

        val newColumns = max(4, (viewWidth / renderer.fontWidth).toInt())
        val newRows = max(4, (viewHeight - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing)

        val em = emulator
        if (em != null && (newColumns != em.columns || newRows != em.rows)) {
            // The soft keyboard animates the view height over many frames. Debounce the
            // reflow so the FINAL layout is computed once (and the remote pty is resized
            // once) instead of reflowing on every animation frame - this is what makes the
            // keyboard appear to "scroll slowly". The first layout stays immediate.
            removeCallbacks(delayedResizeRunnable)
            postDelayed(delayedResizeRunnable, RESIZE_DEBOUNCE_MS)
            return
        }

        if (em == null || newColumns != em.columns || newRows != em.rows) {
            session.updateSize(newColumns, newRows)
            emulator = session.emulator
            client.onEmulatorSet()

            // Keep the blinker pointed at the current emulator across a session change:
            terminalCursorBlinkerRunnable?.setEmulator(emulator)

            updateTopRow(0)
            scrollTo(0, 0)
            invalidate()
        }
    }

    /**
     * Coalesced re-size used while the on-screen keyboard slides in or out. Unlike the
     * naive version this actually PERFORMS the resize (the previous one re-entered
     * updateSize() and re-debounced forever, so zoom/resize never applied).
     */
    private val delayedResizeRunnable = Runnable {
        val em = emulator ?: return@Runnable
        val session = termSession ?: return@Runnable
        val newColumns = max(4, (width / renderer.fontWidth).toInt())
        val newRows = max(4, (height - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing)
        if (newColumns != em.columns || newRows != em.rows) {
            session.updateSize(newColumns, newRows)
            emulator = session.emulator
            client.onEmulatorSet()
            terminalCursorBlinkerRunnable?.setEmulator(emulator)
            updateTopRow(0)
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val em = emulator
        if (em == null) {
            canvas.drawColor(BACKGROUND_WITHOUT_SESSION)
        } else {
            // Render the terminal and highlight the selected rectangle, if any.
            val sel = defaultSelectors
            textSelectionCursorController?.getSelectors(sel)

            // The SSH reader thread appends bytes under the emulator monitor
            // (TerminalEmulator.append() is synchronized); render under that same monitor
            // so a frame never reads a half-appended row (torn frame).
            synchronized(em) {
                renderer.render(em, canvas, topRow, sel[0], sel[1], sel[2], sel[3])
            }

            // Render the text selection handles.
            renderTextSelection()
        }
    }

    /** The session this view is showing, for callers that only read it. */
    val currentSession: TermSession? get() = termSession

    /**
     * Copy the current text selection to the clipboard, if one is active.
     *
     * @return true if text was copied, false if there was no selection to copy.
     */
    fun copySelection(): Boolean {
        val em = emulator
        val controller = textSelectionCursorController
        if (em == null || controller == null || !controller.isActive()) {
            return false
        }
        val sel = IntArray(4)
        controller.getSelectors(sel) // [y1, y2, x1, x2]
        val text = synchronized(em) { em.screen.getSelectedText(sel[2], sel[0], sel[3], sel[1]) }.trim()
        if (text.isEmpty()) {
            return false
        }
        termSession?.onCopyTextToClipboard(text)
        stopTextSelectionMode()
        return true
    }

    /** Paste the system clipboard into the terminal (honours bracketed paste). */
    fun pasteClipboard() {
        val em = emulator ?: return
        exitScreenCopyModeIfNeeded()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val data = clipboard.primaryClip ?: return
        if (data.itemCount == 0) return
        val text = data.getItemAt(0).coerceToText(context)
        if (!TextUtils.isEmpty(text)) {
            em.paste(text.toString())
        }
    }

    private fun visibleText(): CharSequence {
        val em = emulator ?: return ""
        return synchronized(em) { em.screen.getSelectedText(0, topRow, em.columns, topRow + em.rows) }
    }

    fun getCursorX(x: Float): Int = (x / renderer.fontWidth).toInt()

    fun getCursorY(y: Float): Int = ((y - CURSOR_Y_TOUCH_OFFSET) / renderer.fontLineSpacing + topRow).toInt()

    fun getPointX(cx: Int): Int {
        val em = emulator ?: return 0
        return Math.round(min(cx, em.columns) * renderer.fontWidth)
    }

    fun getPointY(cy: Int): Int = (cy - topRow) * renderer.fontLineSpacing

    /**
     * Notified whenever the viewport moves, so the UI can offer a way back to the live
     * prompt (see TerminalScreen's "Back to live" control). Called on the UI thread.
     */
    fun interface OnScrollPositionChanged {
        /** @param atLive true when the viewport shows the live prompt (no scrollback). */
        fun onScrollPositionChanged(atLive: Boolean)
    }

    private var scrollPositionListener: OnScrollPositionChanged? = null

    fun setOnScrollPositionChanged(listener: OnScrollPositionChanged?) {
        scrollPositionListener = listener
        listener?.onScrollPositionChanged(topRow == 0)
    }

    /**
     * Notified when the remote GNU Screen window enters or leaves its copy-mode scrollback,
     * so the UI can show the state (see TerminalScreen's history chip). Called on the UI
     * thread.
     */
    fun interface OnScreenCopyModeChanged {
        fun onScreenCopyModeChanged(copyModeActive: Boolean)
    }

    private var copyModeListener: OnScreenCopyModeChanged? = null

    fun setOnScreenCopyModeChanged(listener: OnScreenCopyModeChanged?) {
        copyModeListener = listener
        listener?.onScreenCopyModeChanged(copyModeActive)
    }

    /** True while the remote GNU Screen window is in copy mode (its own scrollback). */
    val isScreenCopyMode: Boolean get() = copyModeActive

    /**
     * Enter or leave GNU Screen's copy-mode scrollback. No-op when already in the requested
     * state, so the UI chip can never desync by double-toggling.
     */
    fun setScreenCopyMode(enabled: Boolean) {
        val session = termSession ?: return
        if (copyModeActive == enabled) return
        session.write(if (enabled) ScreenCopyMode.ENTER else ScreenCopyMode.EXIT)
        copyModeActive = enabled
        // Opening copy mode starts at the live prompt; leaving it forgets the count.
        copyModeRows = 0
        copyModeListener?.onScreenCopyModeChanged(copyModeActive)
    }

    /**
     * Scroll screen's own scrollback by [rowsBack] lines (positive = back in time) and keep
     * the running distance from the live prompt, so a forward scroll that lands back at the
     * prompt leaves copy mode by itself.
     */
    private fun scrollScreenCopyMode(rowsBack: Int) {
        val session = termSession ?: return
        val steps = ScreenCopyMode.stepCount(rowsBack)
        if (steps == 0) return
        session.write(ScreenCopyMode.scrollCommands(rowsBack))
        if (copyModeRows < 0) return // count lost (HOME/`g`): never guess an exit
        copyModeRows = max(0, copyModeRows + if (rowsBack > 0) steps else -steps)
        if (copyModeRows == 0) exitScreenCopyModeIfNeeded()
    }

    /** Keep [copyModeRows] honest for a copy-mode key sent by [handleKeyCode]. */
    private fun trackCopyModeKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> applyCopyModeDelta(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> applyCopyModeDelta(-1)
            // A copy-mode "page" here is two half-screens (see ScreenCopyMode), i.e. exactly
            // one screen height.
            KeyEvent.KEYCODE_PAGE_UP -> applyCopyModeDelta(emulator?.rows ?: 0)
            KeyEvent.KEYCODE_PAGE_DOWN -> applyCopyModeDelta(-(emulator?.rows ?: 0))
            // `g` jumps to the top of an arbitrarily long buffer: the distance can no longer
            // be counted, so stop trying (typing/ESC/End still get out).
            KeyEvent.KEYCODE_MOVE_HOME -> copyModeRows = -1
        }
    }

    private fun applyCopyModeDelta(delta: Int) {
        if (copyModeRows < 0) return
        copyModeRows = max(0, copyModeRows + delta)
        if (copyModeRows == 0) exitScreenCopyModeIfNeeded()
    }

    /**
     * Convenience for the UI toggle (the History chip on the terminal screen).
     *
     * Opening copy mode parks screen's copy cursor on the LIVE line, so the only visible
     * change would be screen's own "Copy mode - Column x Line y" banner and the user still
     * has to discover dragging. To make the toggle immediately useful it therefore jumps one
     * full screen into the server-side scrollback (the same PAGE_UP key and row accounting
     * as a hardware PGUP while reading history). Drags that open copy mode automatically
     * (doScroll) deliberately do NOT jump - they already arrived with one scroll step under
     * the finger.
     */
    fun toggleScreenCopyMode() {
        if (copyModeActive) {
            setScreenCopyMode(false)
            return
        }
        setScreenCopyMode(true)
        if (copyModeActive) {
            termSession?.write(ScreenCopyMode.FULL_PAGE_UP)
            trackCopyModeKey(KeyEvent.KEYCODE_PAGE_UP)
        }
    }

    /**
     * Leave copy mode before anything the user means as input is sent. Explicit on purpose:
     * screen leaves copy mode by itself on any key it does not recognise, which would leave
     * the app believing it was still scrolling a pager.
     */
    fun exitScreenCopyModeIfNeeded() {
        if (!copyModeActive) return
        termSession?.write(ScreenCopyMode.EXIT)
        copyModeActive = false
        copyModeRows = 0
        copyModeListener?.onScreenCopyModeChanged(false)
    }

    /**
     * True while the user is reading history rather than the live prompt - either in this
     * view's own viewport or inside GNU Screen's copy-mode scrollback.
     */
    fun isScrolledBack(): Boolean = topRow != 0 || copyModeActive

    /** Put the display back on the live prompt (leaving screen's copy mode if open). */
    fun scrollToLive() {
        val wasCopyMode = copyModeActive
        if (wasCopyMode) exitScreenCopyModeIfNeeded()
        if (topRow == 0) {
            if (wasCopyMode) invalidate()
            return
        }
        updateTopRow(0)
        scrollTo(0, 0)
        invalidate()
    }

    /**
     * The single place the viewport is moved by anything other than an explicit
     * [topRow] assignment, so the scroll listener can never drift out of sync with what is
     * on screen. Clamps to the available scrollback.
     */
    private fun updateTopRow(newTopRow: Int) {
        val history = emulator?.screen?.activeTranscriptRows ?: 0
        val clamped = min(0, max(-history, newTopRow))
        if (clamped == topRow) return
        topRow = clamped
    }

    /** AutoFill support: whatever is offered goes to the shell as input. */
    override fun autofill(value: AutofillValue) {
        if (value.isText) {
            exitScreenCopyModeIfNeeded()
            termSession?.write(value.textValue.toString())
        }
    }

    override fun getAutofillType(): Int = AUTOFILL_TYPE_TEXT

    override fun getAutofillValue(): AutofillValue = AutofillValue.forText("")

    /**
     * Set the terminal cursor blinker rate. It must be between
     * [TERMINAL_CURSOR_BLINK_RATE_MIN] and [TERMINAL_CURSOR_BLINK_RATE_MAX], otherwise
     * blinking is disabled.
     *
     * [setTerminalCursorBlinkerState] must be called afterwards for a new rate to take
     * effect, unless it disables blinking.
     *
     * @return true if the rate was accepted, false if it was out of range.
     */
    @Synchronized
    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        val result: Boolean

        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            client.logError(
                LOG_TAG,
                "The cursor blink rate must be in between " +
                    "$TERMINAL_CURSOR_BLINK_RATE_MIN-$TERMINAL_CURSOR_BLINK_RATE_MAX: $blinkRate",
            )
            terminalCursorBlinkerRate = 0
            result = false
        } else {
            client.logVerbose(LOG_TAG, "Setting cursor blinker rate to $blinkRate")
            terminalCursorBlinkerRate = blinkRate
            result = true
        }

        if (terminalCursorBlinkerRate == 0) {
            client.logVerbose(LOG_TAG, "Cursor blinker disabled")
            stopTerminalCursorBlinker()
        }

        return result
    }

    /**
     * Start or stop the cursor blinker. It only starts when the rate is non-zero and inside
     * [TERMINAL_CURSOR_BLINK_RATE_MIN]..[TERMINAL_CURSOR_BLINK_RATE_MAX].
     *
     * Call this when the view is resumed or stopped so the blinker does not run while the
     * screen is not visible. When starting on resume, wait for
     * [TerminalViewClient.onEmulatorSet] after [attachSession] for the first session:
     * calling it straight after attachSession can do nothing, because updateSize() returns
     * early while width/height are still 0 (it runs again from onSizeChanged). Calling it on
     * resume once an emulator exists is still necessary, since onEmulatorSet() is not
     * re-delivered when the activity comes back after the display timed out.
     *
     * It also belongs on the
     * [com.sangiorgisrl.screenlink.terminal.emulator.TerminalSessionClient.onTerminalCursorStateChange]
     * callback, so the blinker stops when the emulator stops showing a cursor, and after a
     * [TermSession.reset] in case the blinker was switched off before the reset.
     *
     * The blinker is a [Runnable] on the main looper that toggles the cursor state and
     * re-posts itself every [terminalCursorBlinkerRate] ms; stopping it just cancels the
     * pending callbacks. No thread of our own, and UI updates have to be posted to the main
     * looper anyway.
     *
     * In editors like nano the cursor state flips `off -> on -> off -> on` very quickly while
     * keys are held, so each "on" is usually cancelled by the next "off" before it runs. That
     * is normal, which is why the log line below is gated on key logging. Blinking starts
     * with the cursor visible so it does not appear to vanish when a key goes down.
     *
     * @param startOnlyIfCursorEnabled when true, also require that the emulator is showing
     *                                 a cursor at all.
     */
    @Synchronized
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker()

        val em = emulator ?: return

        em.setCursorBlinkingEnabled(false)

        if (!start) return

        // If cursor blinker is not enabled or is not valid
        if (terminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN ||
            terminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX
        ) {
            return
        } else if (startOnlyIfCursorEnabled && !em.isCursorEnabled) {
            logKeyEvents("Ignoring call to start cursor blinker since cursor is not enabled")
            return
        }

        // Start cursor blinker runnable
        logKeyEvents("Starting cursor blinker with the blink rate $terminalCursorBlinkerRate")
        if (terminalCursorBlinkerHandler == null) {
            terminalCursorBlinkerHandler = Handler(Looper.getMainLooper())
        }
        terminalCursorBlinkerRunnable = CursorBlinker(em, terminalCursorBlinkerRate).also {
            em.setCursorBlinkingEnabled(true)
            it.run()
        }
    }

    /** Cancel the terminal cursor blinker callbacks. */
    private fun stopTerminalCursorBlinker() {
        val handler = terminalCursorBlinkerHandler
        val runnable = terminalCursorBlinkerRunnable
        if (handler != null && runnable != null) {
            logKeyEvents("Stopping cursor blinker")
            handler.removeCallbacks(runnable)
        }
    }

    private inner class CursorBlinker(
        private var blinkEmulator: TerminalEmulator?,
        private val blinkRate: Int,
    ) : Runnable {

        /** Starts false so the cursor is visible right after the first toggle. */
        private var cursorVisible = false

        fun setEmulator(emulator: TerminalEmulator?) {
            blinkEmulator = emulator
        }

        override fun run() {
            try {
                blinkEmulator?.let { em ->
                    // Flip the blink state and invalidate, so onDraw() -> TerminalRenderer
                    // consults TerminalEmulator.shouldCursorBeVisible() again.
                    cursorVisible = !cursorVisible
                    em.setCursorBlinkState(cursorVisible)
                    this@TerminalView.invalidate()
                }
            } finally {
                // Re-post with the blink delay to flip the state again.
                terminalCursorBlinkerHandler?.postDelayed(this, blinkRate.toLong())
            }
        }
    }

    // --- Text selection handles and the floating toolbar ---------------------

    private fun getTextSelectionCursorController(): TextSelectionCursorController {
        var controller = textSelectionCursorController
        if (controller == null) {
            controller = TextSelectionCursorController(this)
            textSelectionCursorController = controller
        }
        val observer = viewTreeObserver
        if (observer != null) {
            observer.addOnTouchModeChangeListener(controller)
        }
        return controller
    }

    private fun showTextSelectionCursors(event: MotionEvent) {
        getTextSelectionCursorController().show(event)
    }

    private fun hideTextSelectionCursors(): Boolean = getTextSelectionCursorController().hide()

    private fun renderTextSelection() {
        textSelectionCursorController?.render()
    }

    fun isSelectingText(): Boolean = textSelectionCursorController?.isActive() ?: false

    private fun getTextSelectionActionMode(): ActionMode? = textSelectionCursorController?.getActionMode()

    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) {
            return
        }

        showTextSelectionCursors(event)
        client.copyModeChanged(isSelectingText())

        invalidate()
    }

    fun stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            client.copyModeChanged(isSelectingText())
            invalidate()
        }
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        textSelectionCursorController?.decrementYTextSelectionCursors(decrement)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        textSelectionCursorController?.let {
            viewTreeObserver.addOnTouchModeChangeListener(it)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        textSelectionCursorController?.let { controller ->
            // Prevents the selection PopupWindow from outliving its host window, which the
            // platform then reports as an android.view.WindowLeaked exception.
            stopTextSelectionMode()

            viewTreeObserver.removeOnTouchModeChangeListener(controller)
            controller.onDetached()
        }
    }

    private val showFloatingToolbarRunnable = Runnable {
        getTextSelectionActionMode()?.hide(0) // hide off
    }

    private fun showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            postDelayed(showFloatingToolbarRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }

    fun hideFloatingToolbar() {
        getTextSelectionActionMode()?.hide(-1)
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (getTextSelectionActionMode() == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
            // ACTION_UP falls through to the same handling as ACTION_CANCEL:
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
        }
    }

    /** Log a terminal key/IME event when key logging is on. */
    private fun logKeyEvents(message: String) {
        if (keyLoggingEnabled) client.logInfo(LOG_TAG, message)
    }

}
