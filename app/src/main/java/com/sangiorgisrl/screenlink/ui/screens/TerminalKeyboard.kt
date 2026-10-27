package com.sangiorgisrl.screenlink.ui.screens

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ssh.SshTermSession
import com.sangiorgisrl.screenlink.terminal.view.TerminalView
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Primary key height - a safe touch target. */
private val KEY_HEIGHT = 46.dp
/** Utility (extra-key) rows are slightly denser to save vertical space. */
private val UTILITY_KEY_HEIGHT = 40.dp
/** Compact heights for short windows (landscape phones): the full-height pad
 *  would leave the terminal itself no rows at all. */
private val COMPACT_KEY_HEIGHT = 34.dp
private val COMPACT_UTILITY_KEY_HEIGHT = 30.dp

/** Hold time before a repeatable key starts auto-repeating. */
private const val HOLD_BEFORE_REPEAT_MS = 350L
/** Delay between auto-repeat firings while a key is held. */
private const val REPEAT_INTERVAL_MS = 70L

/**
 * How many symbol keys fit on one row. A full-height window uses four rows of
 * eight; a "Short" window (landscape phone) gets the same 32 symbols in two
 * rows, because four rows of pad plus the fixed rows would push the bottom row
 * of the keyboard - and the terminal itself - off the screen.
 */
private const val SYMBOLS_PER_ROW_FULL = 8
private const val SYMBOLS_PER_ROW_COMPACT = 16

/**
 * The M3 ripple used by every key. Repeatable keys drive their own pointer
 * gesture (they cannot use clickable()), so they need the indication attached
 * explicitly - otherwise a held key only changes background colour and gives no
 * touch feedback at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val KeyRipple = ripple()

/**
 * The app's own terminal keyboard: a symbols page, sticky Shift/Ctrl/Alt
 * latches, copy/paste, and long-press auto-repeat on the editing keys.
 *
 * The top rows are the classic terminal extra-keys layout, with the click logic
 * those keys have always had:
 *   - named keys (ESC, HOME, UP, ...) become a KeyEvent routed through the
 *     emulator's onKeyDown() so the current cursor-key mode is respected;
 *   - character keys go through inputCodePoint(..., ctrl, alt) so CTRL/ALT
 *     latches translate exactly like a real keypress.
 *
 * Below those rows is either the letter pad or the symbols page, plus a bottom
 * row (space, ., backspace, Del, Enter, hide) so the terminal is fully usable
 * without the system keyboard.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TerminalKeyboard(
    terminalView: TerminalView?,
    session: SshTermSession,
    ctrlOn: Boolean,
    altOn: Boolean,
    shiftOn: Boolean,
    capsOn: Boolean,
    showSymbols: Boolean,
    showNumbers: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleCaps: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleNumbers: () -> Unit,
    onToggleVisibility: () -> Unit,
    onShiftConsumed: () -> Unit,
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    /** Short window (landscape phone): denser keys and the letter pad collapsed
     *  by default - the ABC key in the bottom row brings it back. */
    compact: Boolean = false,
    showLetters: Boolean = true,
    onToggleLetters: () -> Unit = {},
    /** True while a dictation attempt is in flight (the mic key then means "stop"). */
    dictateActive: Boolean = false,
    /** Tap the microphone key: start dictating, or end the dictation that is running. */
    onToggleDictate: () -> Unit = {},
) {
    /** Send a printable character through the emulator, applying CTRL/ALT latches. */
    fun sendChar(char: Char) {
        terminalView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        terminalView?.inputCodePoint(char.code, ctrlOn, altOn)
    }

    fun sendLetter(raw: Char) {
        // Caps Lock latches uppercase for letters (like a real keyboard): active when
        // either the CAPS latch is on or the Shift latch is on. Shift is MOMENTARY:
        // it uppercases exactly one letter and then releases, so you cannot get stuck
        // "still typing capitals" (Caps Lock stays sticky for that).
        val useShift = shiftOn || capsOn
        sendChar(if (useShift) raw.uppercaseChar() else raw)
        if (shiftOn && !capsOn) onShiftConsumed()
    }

    /**
     * Send a digit. Digits follow hardware behaviour: CAPS never changes them,
     * SHIFT produces the shifted glyph (! @ # $ % ^ & * ( ) ) and is then released.
     * Without this the SHIFT latch on the number row did nothing at all, so the
     * most-used shell metacharacters (! $ % &) were unreachable.
     */
    fun sendDigit(raw: Char) {
        sendChar(if (shiftOn) KeyboardChars.shifted(raw) else raw)
        if (shiftOn) onShiftConsumed()
    }


    /** Send a named key (ESC / arrows / ...) with the usual latches applied. */
    fun namedKey(name: String, haptic: Boolean = true) {
        if (haptic) terminalView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        ExtraKeyCommands.handleNamed(terminalView, name, ctrlOn, altOn)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // ---- one-tap control signals ------------------------------
            // The keys every terminal user reaches for: no CTRL-latch + letter needed.
            // Sent as raw bytes so latches never interfere (^C is 0x03 regardless of
            // any latched CTRL/SHIFT state).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyButton(label = "^C", width = 1f, onTap = { terminalView?.haptic(); session.write(byteArrayOf(0x03), 0, 1) }, contentDescRes = R.string.key_ctrl_c, compact = compact)
                KeyButton(label = "^D", width = 1f, onTap = { terminalView?.haptic(); session.write(byteArrayOf(0x04), 0, 1) }, contentDescRes = R.string.key_ctrl_d, compact = compact)
                KeyButton(label = "^Z", width = 1f, onTap = { terminalView?.haptic(); session.write(byteArrayOf(0x1A), 0, 1) }, contentDescRes = R.string.key_ctrl_z, compact = compact)
                KeyButton(label = "^\\", width = 1f, onTap = { terminalView?.haptic(); session.write(byteArrayOf(0x1C), 0, 1) }, contentDescRes = R.string.key_ctrl_backslash, compact = compact)
            }

            // ---- extra keys (the two utility rows) --------------------------
            ExtraKeyRow(terminalView, listOf(
                ExtraKey("ESC", width = 7f),
                ExtraKey("123", width = 7f, special = true, highlighted = showNumbers) { terminalView?.haptic(); onToggleNumbers() },
                ExtraKey("?!", width = 7f, special = true, highlighted = showSymbols) { terminalView?.haptic(); onToggleSymbols() },
                ExtraKey("/", width = 6f),
                ExtraKey("-", width = 6f, popup = "|"),
                ExtraKey("HOME", width = 7f, labelRes = R.string.key_label_home),
                ExtraKey("UP", width = 7f, repeat = true, labelRes = R.string.key_label_up),
                ExtraKey("END", width = 7f, labelRes = R.string.key_label_end),
                ExtraKey("PGUP", width = 7f, labelRes = R.string.key_label_pgup),
            ), ctrlOn, altOn, compact)
            ExtraKeyRow(terminalView, listOf(
                ExtraKey("TAB", width = 8f),
                ExtraKey(name = "CTRL", width = 8f, special = true, highlighted = ctrlOn) { terminalView?.haptic(); onToggleCtrl() },
                ExtraKey(name = "ALT", width = 8f, special = true, highlighted = altOn) { terminalView?.haptic(); onToggleAlt() },
                ExtraKey(name = "SHIFT", width = 8f, special = true, highlighted = shiftOn, labelRes = R.string.key_label_shift) { terminalView?.haptic(); onToggleShift() },
                ExtraKey(name = "CAPS", width = 8f, special = true, highlighted = capsOn, labelRes = R.string.key_label_caps) { terminalView?.haptic(); onToggleCaps() },
                ExtraKey("LEFT", width = 7f, repeat = true, labelRes = R.string.key_label_left),
                ExtraKey("DOWN", width = 8f, repeat = true, labelRes = R.string.key_label_down),
                ExtraKey("RIGHT", width = 7f, repeat = true, labelRes = R.string.key_label_right),
                ExtraKey("PGDN", width = 8f, labelRes = R.string.key_label_pgdn),
            ), ctrlOn, altOn, compact)
            ExtraKeyRow(terminalView, listOf(
                // The Scroll up/down keys are gone: a finger drag scrolls whatever
                // scrollback is the right one (see TerminalScrollRouter), the status
                // strip's History chip opens GNU Screen's own scrollback, and its Live
                // pill comes back to the prompt. The width they freed went into the keys
                // that stayed. This row is deliberately the densest one (40 dp tall,
                // 12+12+9+9+9+9+9 weight units): on a 360 dp-wide phone that is ~57 dp
                // for the two label keys and ~43 dp for the square ones - the same
                // footprint the utility rows have always had, above the 24 dp WCAG 2.5.8
                // floor for inline controls, and below the 48 dp M3 guidance that the
                // app reserves for standalone targets (see README "Dense controls").
                ExtraKey("Ctrl+A", width = 12f, special = true) { terminalView?.haptic(); terminalView?.inputCodePoint('a'.code, true, altOn) },
                ExtraKey("Detach", width = 12f, special = true, labelRes = R.string.key_label_detach) { terminalView?.haptic(); val d = byteArrayOf(0x01, 'd'.code.toByte()); session.write(d, 0, 2) },
                ExtraKey("Copy", width = 9f, special = true, labelRes = R.string.key_label_copy) { terminalView?.haptic(); terminalView?.copySelection() },
                ExtraKey("Paste", width = 9f, special = true, labelRes = R.string.key_label_paste) { terminalView?.haptic(); terminalView?.pasteClipboard() },
                ExtraKey("A\u2212", width = 9f, special = true) { terminalView?.haptic(); onZoomOut() },
                ExtraKey("A+", width = 9f, special = true) { terminalView?.haptic(); onZoomIn() },
                // Dictation lives on the utility row rather than the letter pad: it is
                // reachable from the symbols and numbers pages too, and it is the one
                // key whose state has to be readable at a glance (it turns into a
                // "stop" key while listening).
                ExtraKey(
                    name = "DICTATE",
                    width = 9f,
                    special = true,
                    highlighted = dictateActive,
                    icon = if (dictateActive) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescRes = if (dictateActive) R.string.key_dictate_stop else R.string.key_dictate,
                ) { terminalView?.haptic(); onToggleDictate() },
            ), ctrlOn, altOn, compact)

            // ---- numbers (off by default - toggle "123" to add the digit row) ----
            if (showNumbers) {
                // The key LABEL shows the shifted glyph while SHIFT is latched, so the
                // key says exactly what it will send.
                DigitRow(shiftOn, ::sendDigit, restartKeys = listOf(ctrlOn, altOn, shiftOn, capsOn), compact = compact, dense = compact)
            }

            // ---- letters OR symbols (symbols off by default - toggle "?!" to swap) ----
            // In a short window the letter pad starts hidden so the terminal keeps
            // usable rows; the ABC key (bottom row) or "?!" brings a pad back.
            if (showLetters || showSymbols) {
                if (showSymbols) {
                    // Same 32 symbols on every window size; a Short window packs them
                    // into two wide rows so the keyboard keeps fitting (see
                    // SYMBOLS_PER_ROW_*), and the rows are dense to buy back height.
                    KeyboardChars.symbolRows(if (compact) SYMBOLS_PER_ROW_COMPACT else SYMBOLS_PER_ROW_FULL)
                        .forEach { row ->
                            CharRow(row, ::sendChar, restartKeys = listOf(ctrlOn, altOn), compact = compact, dense = compact)
                        }
                } else {
                    LetterRow("q w e r t y u i o p", shiftOn || capsOn, ::sendLetter, restartKeys = listOf(ctrlOn, altOn, shiftOn, capsOn), compact = compact, dense = compact)
                    LetterRow("a s d f g h j k l", shiftOn || capsOn, ::sendLetter, restartKeys = listOf(ctrlOn, altOn, shiftOn, capsOn), compact = compact, dense = compact)
                    LetterRow("z x c v b n m , .", shiftOn || capsOn, ::sendLetter, restartKeys = listOf(ctrlOn, altOn, shiftOn, capsOn), compact = compact, dense = compact)
                }
            }

            // ---- bottom row ---------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SpaceKey(width = if (compact) 20f else 26f, onClick = { sendChar(' ') }, restartKeys = listOf(ctrlOn, altOn), compact = compact)
                KeyButton(label = ".", width = if (compact) 3f else 4f, onTap = { sendChar('.') }, restartKeys = listOf(ctrlOn, altOn), compact = compact)
                KeyButton(label = "\u232B", width = 8f, onTap = { namedKey("BKSP") }, onRepeat = { namedKey("BKSP", haptic = false) }, contentDescRes = R.string.key_backspace, restartKeys = listOf(ctrlOn, altOn), compact = compact)
                KeyButton(
                    label = "Del",
                    width = 8f,
                    onTap = { namedKey("DEL") },
                    onRepeat = { namedKey("DEL", haptic = false) },
                    labelRes = R.string.key_label_del,
                    contentDescRes = R.string.action_delete,
                    restartKeys = listOf(ctrlOn, altOn),
                    compact = compact,
                )
                KeyButton(
                    label = "Enter",
                    width = 13f,
                    onTap = { namedKey("ENTER") },
                    labelRes = R.string.key_label_enter,
                    contentDescRes = R.string.key_enter,
                    restartKeys = listOf(ctrlOn, altOn),
                    compact = compact,
                )
                if (compact) {
                    KeyButton(
                        label = "ABC",
                        width = 6f,
                        onTap = { terminalView?.haptic(); onToggleLetters() },
                        highlighted = showLetters,
                        contentDescRes = R.string.key_letters_toggle,
                        restartKeys = listOf(ctrlOn, altOn),
                        compact = true,
                    )
                }
                IconKey(Icons.Outlined.KeyboardHide, contentDescRes = R.string.key_hide, compact = compact) { terminalView?.haptic(); onToggleVisibility() }
            }
        }
    }
}

private fun TerminalView.haptic() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * One extra-key descriptor: geometry, labels and what the press sends.
 *
 * [name] is the COMMAND - the key name or character sent to the emulator,
 * which is protocol-level and never translated. [labelRes] is what the key shows
 * when the display text should be localized (0 = show [name] verbatim, which is
 * right for glyphs like ESC, "/", "^C" that must stay exactly as they look).
 */
private data class ExtraKey(
    val name: String,
    val width: Float = 8f,
    val popup: String? = null,
    val special: Boolean = false,
    val highlighted: Boolean = false,
    val repeat: Boolean = false,
    val repeatIntervalMs: Long? = null,
    @StringRes val labelRes: Int = 0,
    /**
     * Draw an icon instead of a word. Used by dictation, where the glyph is
     * language-independent and the state (listening / not) has to be readable in a
     * glance; the spoken name still comes from [contentDescRes].
     */
    val icon: ImageVector? = null,
    @StringRes val contentDescRes: Int = 0,
    val onClick: (() -> Unit)? = null,
)

private fun String.normalized(): String = this.uppercase(Locale.ROOT).trim()

/** Key name -> KeyEvent keycode: the standard terminal extra-key names. */
private object ExtraKeyCommands {
    val PRIMARY_KEY_CODES: Map<String, Int> = mapOf(
        "SPACE" to KeyEvent.KEYCODE_SPACE, "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB, "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END, "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN, "INS" to KeyEvent.KEYCODE_INSERT,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL, "BKSP" to KeyEvent.KEYCODE_DEL,
        "UP" to KeyEvent.KEYCODE_DPAD_UP, "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
    )

    /** Named keys go through onKeyDown() as a KeyEvent; anything else is typed. */
    fun handleNamed(view: TerminalView?, keyName: String, ctrlDown: Boolean, altDown: Boolean) {
        val key = keyName.normalized()
        val keyCode = PRIMARY_KEY_CODES[key]
        if (keyCode != null) {
            var meta = 0
            if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (altDown) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            val event = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            view?.onKeyDown(keyCode, event)
        } else {
            // characters (e.g. "/" "|" "~" "-" "=" ".")
            keyName.split(",").last().codePoints().forEach { cp ->
                view?.inputCodePoint(cp, ctrlDown, altDown)
            }
        }
    }
}

@Composable
private fun ExtraKeyRow(
    view: TerminalView?,
    keys: List<ExtraKey>,
    ctrlOn: Boolean,
    altOn: Boolean,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { k ->
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            // What the key SHOWS (localized when it is a word) vs. what it SENDS
            // (k.name, always the command that is sent).
            val display = if (k.labelRes != 0) stringResource(k.labelRes) else k.name
            // Resolved OUTSIDE the semantics block: a composable call cannot happen
            // inside the semantics lambda.
            // An icon key has no word to speak, so its name comes from contentDescRes.
            val describe = when {
                k.contentDescRes != 0 -> stringResource(k.contentDescRes)
                display.isEmpty() -> null
                else -> a11yLabel(display)
            }
            val onKeyClick: () -> Unit = {
                if (k.special && k.onClick != null) k.onClick() else ExtraKeyCommands.handleNamed(view, k.name, ctrlOn, altOn)
            }
            // restartKeys = current latches: toggling CTRL/ALT restarts the pointer
            // handler so the NEXT press sees the new latch (fixes "ctrl stays on").
            val pressModifier = if (k.repeat) {
                Modifier.repeatableKey(interactionSource, onKeyClick, onKeyClick, k.repeatIntervalMs ?: REPEAT_INTERVAL_MS, restartKeys = listOf(ctrlOn, altOn))
            } else {
                Modifier.combinedClickable(
                    // The interaction source MUST be passed: without it the
                    // clickable() gesture emits its press into a private source, so
                    // `pressed` below never turns true and ESC/TAB/CTRL/ALT/SHIFT/...
                    // gave no pressed feedback at all.
                    interactionSource = interactionSource,
                    // The ripple is drawn by the explicit .indication() below (shared
                    // with the auto-repeat keys); asking clickable() for its own as
                    // well would stack two ripples on the same source and darken the
                    // press beyond the M3 spec.
                    indication = null,
                    role = Role.Button,
                    onClick = onKeyClick,
                    onLongClick = {
                        val popup = k.popup
                        if (popup != null && popup.isNotEmpty()) {
                            view?.inputCodePoint(popup[0].code, false, false)
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .weight(k.width)
                    .height(if (compact) COMPACT_UTILITY_KEY_HEIGHT else UTILITY_KEY_HEIGHT)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (k.highlighted) MaterialTheme.colorScheme.primary
                        else if (pressed) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .indication(interactionSource, KeyRipple)
                    .then(pressModifier)
                    .then(
                        if (describe != null) {
                            Modifier.semantics { contentDescription = describe }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val keyIcon = k.icon
                if (keyIcon != null) {
                    Icon(
                        keyIcon,
                        // The key's own semantics carry the spoken name (the `describe`
                        // above), so the glyph stays decorative instead of being
                        // announced twice by TalkBack.
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (k.highlighted) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Text(
                        // Localized labels can be longer than the English ones ("PGUP" ->
                        // "RE PAG"), so the key clips instead of pushing its neighbours out.
                        text = display,
                        fontSize = 12.sp,
                        fontWeight = if (k.highlighted) FontWeight.Bold else FontWeight.Normal,
                        color = if (k.highlighted) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LetterRow(
    chars: String,
    shift: Boolean,
    onChar: (Char) -> Unit,
    restartKeys: List<Any?> = emptyList(),
    compact: Boolean = false,
    dense: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chars.split(' ').filter { it.isNotEmpty() }.forEach { ch ->
            KeyButton(
                label = if (shift) ch[0].uppercaseChar().toString() else ch.toString(),
                width = 1f,
                onTap = { onChar(ch[0]) },
                restartKeys = restartKeys,
                compact = compact,
                dense = dense,
            )
        }
    }
}

/**
 * The digit row. Unlike letters, digits are shifted by SHIFT only (never CAPS), and
 * the label shows the shifted glyph while SHIFT is latched.
 */
@Composable
private fun DigitRow(
    shift: Boolean,
    onDigit: (Char) -> Unit,
    restartKeys: List<Any?> = emptyList(),
    compact: Boolean = false,
    dense: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        "1 2 3 4 5 6 7 8 9 0".split(' ').forEach { ch ->
            KeyButton(
                label = if (shift) KeyboardChars.shifted(ch[0]).toString() else ch,
                width = 1f,
                onTap = { onDigit(ch[0]) },
                restartKeys = restartKeys,
                compact = compact,
                dense = dense,
            )
        }
    }
}

@Composable
private fun CharRow(
    chars: List<Char>,
    onChar: (Char) -> Unit,
    restartKeys: List<Any?> = emptyList(),
    compact: Boolean = false,
    dense: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chars.forEach { ch ->
            KeyButton(
                label = ch.toString(),
                width = 1f,
                onTap = { onChar(ch) },
                restartKeys = restartKeys,
                compact = compact,
                dense = dense,
            )
        }
    }
}

/** A standard key: tap fires, and if [onRepeat] is given it auto-repeats on hold. */
@Composable
private fun RowScope.KeyButton(
    label: String,
    width: Float,
    onTap: () -> Unit,
    onRepeat: (() -> Unit)? = null,
    /** Accessibility description resource (0 = derive a spoken name from the label). */
    @StringRes contentDescRes: Int = 0,
    /** Localized display text (0 = show [label] verbatim, as glyphs must). */
    @StringRes labelRes: Int = 0,
    restartKeys: List<Any?> = emptyList(),
    highlighted: Boolean = false,
    compact: Boolean = false,
    /** Pad rows in a Short window use the denser utility height (see below). */
    dense: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val text = if (labelRes != 0) stringResource(labelRes) else label
    val description = if (contentDescRes != 0) stringResource(contentDescRes) else a11yLabel(text)
    Box(
        modifier = Modifier
            .weight(width)
            .height(
                when {
                    compact && dense -> COMPACT_UTILITY_KEY_HEIGHT
                    compact -> COMPACT_KEY_HEIGHT
                    else -> KEY_HEIGHT
                }
            )
            .clip(MaterialTheme.shapes.extraSmall)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary
                else if (pressed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .indication(interactionSource, KeyRipple)
            .repeatableKey(interactionSource, onTap, onRepeat, restartKeys = restartKeys)
            .then(
                Modifier.semantics {
                    this.contentDescription = description
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Accessibility label for a key: a translated word for punctuation, the label
 * itself for letters, digits and named keys (ESC, Copiar, ...).
 *
 * Lives in the composable layer (not in [KeyboardChars]) because it needs the
 * current locale; [KeyboardChars.spokenNameRes] holds the pure mapping.
 */
@Composable
private fun a11yLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.length != 1) return trimmed
    val c = trimmed[0]
    if (c.isLetterOrDigit()) return trimmed
    val res = KeyboardChars.spokenNameRes(c)
    return if (res != 0) stringResource(res) else trimmed
}

@Composable
private fun RowScope.SpaceKey(
    width: Float,
    onClick: () -> Unit,
    restartKeys: List<Any?> = emptyList(),
    compact: Boolean = false,
) {
    KeyButton(
        label = "space",
        width = width,
        onTap = onClick,
        labelRes = R.string.key_label_space,
        contentDescRes = R.string.sym_space,
        restartKeys = restartKeys,
        compact = compact,
    )
}

@Composable
private fun RowScope.IconKey(
    icon: ImageVector,
    @StringRes contentDescRes: Int,
    compact: Boolean = false,
    width: Float = 5f,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val description = stringResource(contentDescRes)
    Box(
        modifier = Modifier
            .weight(width)
            .height(if (compact) COMPACT_KEY_HEIGHT else KEY_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(
                if (pressed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .indication(interactionSource, KeyRipple)
            .repeatableKey(interactionSource, onClick, null)
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * Pointer-input handler for a keyboard key: reports actual presses through
 * [MutableInteractionSource] (drives the pressed background), fires [onTap]
 * on release, and when [repeatAction] is set auto-repeats it while held down.
 */
// The press/hold/repeat gesture fundamentally needs two awaitPointerEventScope blocks
// (first down, then up) so a concurrent auto-repeat coroutine can run between them - the
// "dropped events" these heuristics warn about only matter when a second finger lands
// mid-hold, which is exactly the case awaitOwnUpOrCancellation() handles correctly (it
// tracks only its own pointer). The gesture is consumed precisely.
@Suppress("MultipleAwaitPointerEventScopes")
private fun Modifier.repeatableKey(
    source: MutableInteractionSource,
    onTap: () -> Unit,
    repeatAction: (() -> Unit)?,
    repeatIntervalMs: Long = REPEAT_INTERVAL_MS,
    restartKeys: List<Any?> = emptyList(),
): Modifier = this
    // Screen readers / keyboard navigation cannot drive a pointer-only handler, so
    // expose a single activation: TalkBack and a focused hardware keyboard can then
    // trigger this key (one step for auto-repeat keys) without touch input.
    .semantics {
        role = Role.Button
        onClick(label = null) {
            onTap()
            true
        }
    }
    // CRITICAL: pointerInput() only re-runs its block when the KEYS change. The block
    // captures `onTap`/`repeatAction`, which in turn capture the CTRL/ALT/SHIFT/CAPS
    // latch state. Passing the latches as keys restarts the handlers whenever a latch
    // toggles, so a key tapped AFTER turning CTRL off really sends a plain letter
    // (without this, the handlers keep the latch value from when they first composed -
    // the "CTRL is off but still on" bug).
    .pointerInput(source, *restartKeys.toTypedArray()) {
    val bounds = size
    // The pointerInput block is a plain suspend coroutine; give ourselves a
    // real CoroutineScope so we can launch the auto-repeater. Event suspension
    // happens only inside awaitPointerEventScope() blocks (which is where the
    // restricted-scope rule applies, and we only call scope-local waits there).
    coroutineScope {
        while (true) {
            // The down/up events are ASSIGNED inside the scope instead of returned
            // out of it: Compose queues pointer events inside awaitPointerEventScope,
            // and letting values escape it is exactly what the
            // ReturnFromAwaitPointerEventScope check warns about (events can be lost
            // when a second scope restarts). Assigning keeps the gesture consumed
            // where it was actually observed.
            lateinit var down: PointerInputChange
            awaitPointerEventScope { down = awaitFirstDown(requireUnconsumed = false) }
            down.consume()
            val press = PressInteraction.Press(down.position)
            // emit(), not tryEmit(): the interaction source is a shared, zero-buffer
            // flow and tryEmit DROPS the interaction when a collector is mid-suspend,
            // which is why some presses never showed their pressed state.
            source.emit(press)
            val repeater = repeatAction?.let { action ->
                launch {
                    delay(HOLD_BEFORE_REPEAT_MS)
                    while (isActive) {
                        action()
                        delay(repeatIntervalMs)
                    }
                }
            }
            var up: PointerInputChange? = null
            try {
                // Blocks until THIS pointer is up, or this gesture is cancelled.
                awaitPointerEventScope { up = awaitOwnUpOrCancellation(down.id, bounds) }
            } finally {
                repeater?.cancel()
            }
            val released = up
            if (released != null) {
                released.consume()
                source.emit(PressInteraction.Release(press))
                // A tap only fires on a real release INSIDE the key. Sliding the finger
                // off the key (or a second finger taking over) cancels it, exactly like
                // every other Material control - previously the key typed anyway.
                onTap()
            } else {
                source.emit(PressInteraction.Cancel(press))
            }
        }
    }
}

/**
 * Wait for [downId] to be released, or for its gesture to be cancelled.
 *
 * This deliberately replaces waitForUpOrCancellation(): that helper gives up when ANY
 * pointer in the event is consumed or has left its bounds, so typing with two thumbs
 * (or rolling across keys) cancelled the key that was actually being pressed. Here only
 * our own pointer matters, and "left the key" is measured against this key's own box.
 */
private suspend fun AwaitPointerEventScope.awaitOwnUpOrCancellation(
    downId: PointerId,
    bounds: IntSize,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == downId } ?: return null
        if (change.changedToUp()) return change
        if (change.isConsumed) return null
        val position = change.position
        if (position.x < 0f || position.y < 0f ||
            position.x >= bounds.width || position.y >= bounds.height
        ) {
            return null
        }
    }
}
