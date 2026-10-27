package com.sangiorgisrl.screenlink.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.edit
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.sangiorgisrl.screenlink.terminal.emulator.TermSession
import com.sangiorgisrl.screenlink.terminal.emulator.TerminalSessionClient
import com.sangiorgisrl.screenlink.terminal.view.TerminalView
import com.sangiorgisrl.screenlink.terminal.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * {@link TerminalViewClient} for the SSH terminal screen.
 *
 * Wires the terminal view the way a terminal emulator has to be wired:
 * - tapping the screen shows the soft keyboard (InputMethodManager, with a delay)
 * - pinch-zoom steps the font size up/down (onScale() -> changeFontSize)
 */
class DefaultTerminalViewClient(
    private val terminalView: TerminalView,
    private val onTerminalTapped: () -> Unit = {},
) : TerminalViewClient {

    companion object {
        private const val PREFS = "screenlink_terminal"
        private const val KEY_FONT_SIZE_SP = "terminal_font_size_sp"

        /** Base / limits stored in sp (device-independent; converted to px at apply time). */
        private const val DEFAULT_FONT_SIZE_SP = 14f
        private const val MIN_FONT_SIZE_SP = 10f
        private const val MAX_FONT_SIZE_SP = 28f

        /** One A+ / A- / pinch zoom step, in sp. */
        private const val FONT_STEP_SP = 2f

        /** Read the persisted terminal font size (sp), clamped to sane bounds. */
        fun loadFontSizeSp(context: Context): Float =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
                .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

        private fun saveFontSizeSp(context: Context, fontSizeSp: Float) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putFloat(KEY_FONT_SIZE_SP, fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)) }
        }
    }

    /** Current terminal font size in sp; persisted so zoom survives app restarts. */
    private var currentFontSp: Float

    private val context: Context get() = terminalView.context

    init {
        currentFontSp = loadFontSizeSp(context)
        // Apply the persisted size immediately (before the session attaches), so the
        // emulator lays out at the user's saved zoom on every re-entry.
        terminalView.setTextSize(spToPx(currentFontSp))
    }

    /** sp -> px without touching the deprecated DisplayMetrics.scaledDensity field. */
    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            terminalView.resources.displayMetrics,
        ).roundToInt()

    private val showKeyboardRunnable = Runnable {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        try {
            imm?.showSoftInput(terminalView, 0)
        } catch (_: Exception) {
            // Ignore - keyboard may already be visible.
        }
    }

    /** Call after attach to request the software keyboard (the IME needs a delay). */
    fun showKeyboard() {
        terminalView.removeCallbacks(showKeyboardRunnable)
        terminalView.postDelayed(showKeyboardRunnable, 300)
    }

    fun hideKeyboard() {
        terminalView.removeCallbacks(showKeyboardRunnable)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }

    override fun onSingleTapUp(e: MotionEvent) {
        onTerminalTapped()
        // The terminal uses its own in-app keyboard - never open the system soft keyboard.
    }

    /** Change the font size by a number of steps (positive = bigger, negative = smaller).
     *  The new size is persisted so it survives app restarts. */
    fun changeFontSizeBy(steps: Int) {
        val next = (currentFontSp + steps * FONT_STEP_SP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (next != currentFontSp) {
            currentFontSp = next
            saveFontSizeSp(context, currentFontSp)
            terminalView.setTextSize(spToPx(currentFontSp))
        }
    }

    /** Pinch zoom: step the font size and reset the accumulated scale. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.95f || scale > 1.05f) {
            changeFontSizeBy(if (scale > 1f) 1 else -1)
            return 1.0f // reset the pinch accumulation, so one pinch steps once
        }
        return scale
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * The IME mode this app relies on. With TYPE_NULL, modern soft keyboards often
     * refuse to show a keyboard or to commit text (some take a special path for
     * TYPE_NULL). The char-based mode uses VISIBLE_PASSWORD|NO_SUGGESTIONS,
     * which every keyboard handles and commits through commitText().
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TermSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    // Modifier latches live in the in-app keyboard (TerminalKeyboard state) and are
    // passed explicitly through inputCodePoint()/handleKeyCode(). The hardware-key
    // path uses the physical modifier keys, so no state belongs here.
    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TermSession): Boolean = false

    override fun onEmulatorSet() {
        // Start the cursor blinker. Besides making the cursor blink,
        // its periodic invalidate() is what repaints newly-arriving bytes - without
        // it the terminal shows a static snapshot and never updates live output.
        if (terminalView.setTerminalCursorBlinkerRate(500)) {
            terminalView.setTerminalCursorBlinkerState(true, false)
        }
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Exception", e)
    }
}

/**
 * Ties the {@link TermSession} callbacks (title, bell, clipboard) to UI side
 * effects like haptics and the system clipboard.
 */
class DefaultTerminalSessionClient(
    private val context: Context,
    private val onTitleChanged: (String) -> Unit = {},
) : TerminalSessionClient {

    private val clipboard get() =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onTextChanged(changedSession: TermSession) = Unit

    override fun onTitleChanged(changedSession: TermSession) {
        changedSession.title?.let { onTitleChanged(it) }
    }

    override fun onSessionFinished(finishedSession: TermSession) = Unit

    override fun onCopyTextToClipboard(session: TermSession, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TermSession) {
        // The selection toolbar's "Paste" action routes here (long-press ActionMode).
        // Read the system clipboard and feed it through the emulator so bracketed
        // paste and newline->CR conversion are handled correctly.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) session.emulator?.paste(text)
    }

    override fun onBell(session: TermSession) {
        runCatching {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onColorsChanged(session: TermSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Exception", e)
    }
}
