package com.sangiorgisrl.screenlink.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.TerminalFocusHolder
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.session.LiveTerminalStore
import com.sangiorgisrl.screenlink.session.LiveTerminalStore.SessionState
import com.sangiorgisrl.screenlink.speech.DictationState
import com.sangiorgisrl.screenlink.speech.SpeechDictation
import com.sangiorgisrl.screenlink.speech.dictationEngines
import com.sangiorgisrl.screenlink.speech.isActive
import com.sangiorgisrl.screenlink.ssh.SshTermSession
import com.sangiorgisrl.screenlink.ui.terminal.DefaultTerminalSessionClient
import com.sangiorgisrl.screenlink.ui.terminal.TerminalOverlayLayout
import com.sangiorgisrl.screenlink.ui.terminal.DefaultTerminalViewClient
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveLayout
import com.sangiorgisrl.screenlink.ui.adaptive.HeightSize
import com.sangiorgisrl.screenlink.ui.theme.TerminalCanvasTheme
import com.sangiorgisrl.screenlink.ui.theme.TerminalPalette
import com.sangiorgisrl.screenlink.ui.theme.statusColors
import com.sangiorgisrl.screenlink.ui.viewmodel.TerminalViewModel
import com.sangiorgisrl.screenlink.terminal.view.TerminalView

/**
 * Applies the terminal window's soft-input behavior.
 *
 * SOFT_INPUT_ADJUST_NOTHING while the terminal is visible (the in-app keyboard can
 * never overlap it) and the normal RESIZE when leaving. The constants are marked
 * deprecated on API 35 in favor of edge-to-edge insets, but there is no equivalent
 * non-deprecated constant and the window-insets path would change the measured
 * layout the scrolling code depends on, so we keep them - deliberately.
 */
@Suppress("DEPRECATION")
private fun setTerminalSoftInputMode(activity: Activity?, terminalVisible: Boolean) {
    val mode = if (terminalVisible) {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    } else {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    activity?.window?.setSoftInputMode(mode)
}

/** Tabbed terminal: each session opened from the connection screens becomes a tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val active = tabs.firstOrNull { it.id == activeId }

    var showCustomKeyboard by remember { mutableStateOf(true) }
    val activity = LocalActivity.current
    // Read once, in the composition: the dictation callbacks run later, from
    // lambdas that are not composable and cannot call LocalContext.current.
    val context = LocalContext.current

    // Live references so the accessory bar can re-focus / re-attach the terminal.
    val terminalViewHolder = remember { mutableStateOf<TerminalView?>(null) }
    val clientHolder = remember { mutableStateOf<DefaultTerminalViewClient?>(null) }
    var viewClientReady by remember { mutableStateOf(false) }

    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var shiftOn by remember { mutableStateOf(false) }
    var capsOn by remember { mutableStateOf(false) }
    var showSymbols by remember { mutableStateOf(false) }
    var showNumbers by remember { mutableStateOf(false) }
    /** True while the terminal viewport is showing scrollback (see the live pill). */
    var scrolledBack by remember { mutableStateOf(false) }
    /**
     * True while the remote GNU Screen window is in its own copy-mode scrollback
     * (see ScreenCopyMode). That history lives on the SERVER, so it is the only way
     * to read output from before this app attached.
     */
    var copyMode by remember { mutableStateOf(false) }

    // ---- dictation (speech-to-text on the in-app keyboard) -------------------
    // One attempt at a time, typed into whichever terminal is active.
    //
    // The text sink reads terminalViewHolder.value AT INSERT TIME instead of capturing
    // a view: switching tabs replaces the AndroidView, and a dictation that finishes
    // after that must land in the terminal the user is looking at now - never in a
    // detached one.
    // The recognizer stack for this device, best first: ML Kit's Advanced mode (the
    // on-device Gemini model, Gemini Nano, through AICore) where the device has it,
    // then the platform recognizer, then ML Kit's Basic on-device model. The chain
    // steps down on its own when an engine is missing, busy, or needs a model it
    // cannot fetch, so an unsupported device still dictates.
    val dictationEngine = remember { dictationEngines(context) }
    val dictation = remember(dictationEngine) {
        SpeechDictation(dictationEngine) { text ->
            terminalViewHolder.value?.let { view ->
                // Typed exactly like the letter keys, with NO CTRL/ALT latch applied:
                // dictated text is text, and a latched CTRL would turn the "c" in
                // "cd" into an interrupt. Each code point goes through the same path a
                // key press takes, so it also leaves screen copy mode and follows the
                // live prompt on its own.
                text.codePoints().forEach { cp -> view.inputCodePoint(cp, false, false) }
            }
        }
    }
    val dictationState by dictation.state.collectAsStateWithLifecycle()

    // RECORD_AUDIO is a runtime permission: the first tap on the microphone asks for
    // it. shouldShowRequestPermissionRationale is the only signal that a refusal was
    // permanent - showing the dialog again there would do nothing at all, so that
    // case offers Settings instead of a retry.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val canAskAgain = !granted && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            )
        dictation.onPermissionResult(granted, canAskAgain)
    }

    val onToggleDictate: () -> Unit = {
        terminalViewHolder.value?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) dictation.toggle() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Back closes the dictation, never the session - and never leaves a live
    // microphone behind when the screen itself goes away.
    BackHandler(enabled = dictationState.isActive) { dictation.cancel() }
    DisposableEffect(dictation) {
        onDispose { dictation.dispose() }
    }

    // Leave automatically once the last tab is closed.
    LaunchedEffect(tabs.isEmpty()) {
        if (tabs.isEmpty()) onBack()
    }

    // The terminal uses a fully in-app keyboard, so the SYSTEM soft keyboard is never
    // shown here: any pending IME is dismissed, and the window will not resize around
    // an IME (SOFT_INPUT_ADJUST_NOTHING). Nothing can overlap the terminal this way.
    LaunchedEffect(Unit) {
        TerminalFocusHolder.terminalVisible = true
        if (activity == null) return@LaunchedEffect
        try {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(activity.window?.decorView?.windowToken, 0)
            setTerminalSoftInputMode(activity, terminalVisible = true)
        } catch (_: Exception) {
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            TerminalFocusHolder.terminalVisible = false
            setTerminalSoftInputMode(activity, terminalVisible = false)
        }
    }

    // Back NEVER disconnects: sessions are owned by the foreground service and keep
    // running while the user is away. The default back navigation simply leaves this
    // screen; the live tabs stay reachable from Home via the "Open terminal" card.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // The terminal itself is edge-to-edge, but keep the title below the
                // status bar (battery/time) so it never overlaps it.
                windowInsets = WindowInsets.statusBars,
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.terminal_title),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (tabs.isEmpty()) {
                                stringResource(R.string.terminal_no_sessions)
                            } else {
                                pluralStringResource(
                                    R.plurals.terminal_session_count,
                                    tabs.size,
                                    tabs.size,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.terminal_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        // The pty canvas is the ANSI default palette, not app chrome (see TerminalPalette).
        containerColor = TerminalPalette.background,
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // A landscape phone window is M3-"Short" height: the full letter-pad
            // keyboard would leave the terminal itself no rows, so the keyboard
            // goes compact there (denser keys, letter pad hidden until "ABC").
            // Tablets and portrait phones keep the full pad.
            val compactKeyboard =
                AdaptiveLayout.heightSizeOf(maxHeight.value.toDouble()) == HeightSize.Short
            // Width of the whole content area, captured where the BoxWithConstraints
            // receiver is still implicit (the overlay row that uses it is nested
            // inside the tab switch further down).
            val contentWidthDp = maxWidth.value
            // The system font scale is part of the arithmetic: a large-text user
            // runs out of room for a label sooner, and the strip must go icon-only
            // before the label wraps to a second line.
            val fontScale = LocalConfiguration.current.fontScale
            // Letter-pad default follows the window shape; the ABC key overrides
            // either way, so the user can always bring the pad back.
            var lettersOverride by remember { mutableStateOf<Boolean?>(null) }
            val showLetters = lettersOverride ?: !compactKeyboard
            // Rotating a phone (or resizing a free-form window) changes the height
            // class, and with it the sensible default - a manual override picked for
            // the old shape must not be carried over into the new one.
            LaunchedEffect(compactKeyboard) { lettersOverride = null }

            Column(modifier = Modifier.fillMaxSize()) {
                if (tabs.isNotEmpty()) {
                    TerminalTabStrip(
                        tabs = tabs,
                        activeId = activeId,
                        onSelect = viewModel::select,
                        onClose = viewModel::closeTab,
                        onAdd = onBack, // go back to the connection screen to add another
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (val s = active?.state) {
                        null -> Unit

                        is SessionState.Connecting -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = TerminalPalette.foreground)
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = stringResource(R.string.terminal_opening),
                                        color = TerminalPalette.foregroundMuted,
                                    )
                                }
                            }
                        }

                        is SessionState.Error -> {
                            val tabId = active?.id
                            StatusPanel(
                                icon = {
                                    Icon(
                                        Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        modifier = Modifier.height(44.dp),
                                    )
                                },
                                title = stringResource(R.string.terminal_could_not_open),
                                message = s.message.rememberResolved(),
                                retryAction = tabId?.let { id -> { viewModel.retryTab(id) } },
                                action = {
                                    BackButton(
                                        onClick = onBack,
                                        label = stringResource(R.string.action_back),
                                    )
                                },
                            )
                        }

                        is SessionState.Finished -> {
                            val tabId = active?.id
                            StatusPanel(
                                icon = null,
                                title = s.reason.rememberResolved(),
                                message = stringResource(R.string.terminal_remote_closed),
                                retryAction = tabId?.let { id -> { viewModel.retryTab(id) } },
                                action = {
                                    BackButton(
                                        onClick = onBack,
                                        label = stringResource(R.string.terminal_back_to_sessions),
                                    )
                                },
                            )
                        }

                        is SessionState.Active -> {
                            val activeSession = s.session
                            AndroidView(
                                factory = { context ->
                                    TerminalView(context, null).apply {
                                        terminalViewHolder.value = this
                                        // The terminal client's init applies the persisted zoom
                                        // on first attach - no need to also size it here.
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        isClickable = true
                                        runCatching {
                                            val m = android.view.View::class.java.getMethod(
                                                "setShowSoftInputOnFocus",
                                                Boolean::class.javaPrimitiveType,
                                            )
                                            m.invoke(this, false)
                                        }
                                    }
                                },
                                update = { view ->
                                    view.isFocusable = true
                                    view.isFocusableInTouchMode = true
                                    view.requestFocus()
                                    // Finger scrolling now STOPS auto-following the live
                                    // prompt while the user reads scrollback, so the UI has
                                    // to say where they are and give a one-tap way back.
                                    view.setOnScrollPositionChanged { atLive -> scrolledBack = !atLive }
                                    view.setOnScreenCopyModeChanged { copyMode = it }
                                    // Only a GNU Screen window has a server-side scrollback
                                    // (copy mode) to scroll into; a plain SSH shell's history
                                    // is already local. The view cannot know this itself.
                                    view.screenCopyModeSupported = activeSession.isScreenSession
                                    if (view.currentSession !== activeSession) {
                                        if (!viewClientReady) {
                                            val client = DefaultTerminalViewClient(
                                                view,
                                                onTerminalTapped = { view.requestFocus() },
                                            )
                                            clientHolder.value = client
                                            view.setTerminalViewClient(client)
                                            viewClientReady = true
                                        }
                                        activeSession.updateTerminalSessionClient(
                                            DefaultTerminalSessionClient(view.context),
                                        )
                                        view.attachSession(activeSession)
                                        view.setFocusable(true)
                                        view.setFocusableInTouchMode(true)
                                        view.requestFocus()
                                    }
                                },
                                onRelease = { view ->
                                    // The view left the composition - including when the Activity is
                                    // destroyed in the background. The SSH sessions live in the
                                    // foreground service, so we do NOT disconnect here.
                                    // Release the session's repaint hook too, so the detached view
                                    // is not pinned by a long-lived backgrounded session.
                                    runCatching {
                                        terminalViewHolder.value?.currentSession?.setOnOutputListener(null)
                                    }
                                    view.setOnScrollPositionChanged(null)
                                    view.setOnScreenCopyModeChanged(null)
                                    terminalViewHolder.value = null
                                    clientHolder.value = null
                                    // A dictation must not type into a view that just left
                                    // the screen (a tab switch or a navigation away).
                                    dictation.cancel()
                                    viewClientReady = false
                                    scrolledBack = false
                                    copyMode = false
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // The dictation strip floats over the BOTTOM of the canvas rather
                    // than joining the column below it: a row that appears and
                    // disappears changes the canvas height, which resizes the remote
                    // pty and makes the server repaint the whole screen - twice per
                    // dictation, while the user is reading output. Over the canvas the
                    // layout never moves; the strip is only up while the user talks.
                    if (dictationState !is DictationState.Off) {
                        DictationBar(
                            state = dictationState,
                            backend = dictation.backend,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onFinish = dictation::stop,
                            onCancel = dictation::cancel,
                            onRetry = dictation::start,
                            onDismiss = dictation::dismiss,
                            onOpenSettings = { openAppSettings(context) },
                        )
                    }
                }

                // Keyboards / show-keyboard bar are only for the ACTIVE session.
                val activeSession = (active?.state as? SessionState.Active)?.session

                // The history toggle and the live/back-to-live status live in their
                // OWN strip between the canvas and the keyboard. They used to float
                // over the canvas, which allowed three collisions: the chip and the
                // pill landing on each other (the Spanish labels are wider), the
                // caption running under the pill, and - in a window shorter than the
                // strip itself - the whole row being drawn on top of the tab strip,
                // because a Box does not clip its children. In flow none of that can
                // happen; the canvas simply keeps the rows that are left.
                //
                // The strip is there for every ACTIVE session rather than only when
                // there is something to show: appearing and disappearing as the user
                // scrolls would resize the pty on every scroll gesture.
                if (activeSession != null) {
                    TerminalStatusStrip(
                        // Only GNU Screen sessions have a server-side scrollback to
                        // reach; a plain SSH shell's history is already local.
                        showHistory = activeSession.isScreenSession,
                        historyActive = copyMode,
                        historyIconOnly = TerminalOverlayLayout.compactStrip(compactKeyboard) ||
                            !TerminalOverlayLayout.showHistoryLabel(
                                widthDp = contentWidthDp.toDouble(),
                                fontScale = fontScale,
                                shortHeight = compactKeyboard,
                            ),
                        showHint = copyMode && TerminalOverlayLayout.showHistoryHint(
                            widthDp = contentWidthDp.toDouble(),
                            shortHeight = compactKeyboard,
                        ),
                        onToggleHistory = {
                            terminalViewHolder.value?.toggleScreenCopyMode()
                            terminalViewHolder.value?.requestFocus()
                        },
                        // Reading history in EITHER scrollback (our viewport or
                        // screen's copy mode) gets the same way back to the prompt.
                        atLive = !scrolledBack && !copyMode,
                        liveIconOnly = TerminalOverlayLayout.compactStrip(compactKeyboard),
                        onBackToLive = {
                            terminalViewHolder.value?.scrollToLive()
                            terminalViewHolder.value?.requestFocus()
                        },
                    )
                }
                if (activeSession != null && !showCustomKeyboard) {
                    ShowKeyboardBar(onClick = { showCustomKeyboard = true })
                }
                if (activeSession != null && showCustomKeyboard) {
                    TerminalKeyboard(
                        terminalView = terminalViewHolder.value,
                        session = activeSession,
                        ctrlOn = ctrlOn,
                        altOn = altOn,
                        shiftOn = shiftOn,
                        capsOn = capsOn,
                        showSymbols = showSymbols,
                        showNumbers = showNumbers,
                        onToggleCtrl = { ctrlOn = !ctrlOn },
                        onToggleAlt = { altOn = !altOn },
                        onToggleShift = { shiftOn = !shiftOn },
                        onToggleCaps = { capsOn = !capsOn },
                        // In a Short window the pad area is ONE page at a time: numbers,
                        // symbols or letters. Stacking them (the full-height layout does)
                        // makes the keyboard taller than the window and pushes its bottom
                        // row - and the terminal - off the screen.
                        onToggleSymbols = {
                            val next = !showSymbols
                            showSymbols = next
                            if (next) {
                                showNumbers = false
                                if (compactKeyboard) lettersOverride = false
                            }
                        },
                        onToggleNumbers = {
                            val next = !showNumbers
                            showNumbers = next
                            if (next) {
                                showSymbols = false
                                if (compactKeyboard) lettersOverride = false
                            }
                        },
                        onToggleVisibility = { showCustomKeyboard = !showCustomKeyboard },
                        onShiftConsumed = { shiftOn = false },
                        onZoomIn = { clientHolder.value?.changeFontSizeBy(1); terminalViewHolder.value?.requestFocus() },
                        onZoomOut = { clientHolder.value?.changeFontSizeBy(-1); terminalViewHolder.value?.requestFocus() },
                        compact = compactKeyboard,
                        showLetters = showLetters,
                        dictateActive = dictationState.isActive,
                        onToggleDictate = onToggleDictate,
                        onToggleLetters = {
                            val next = !showLetters
                            lettersOverride = next
                            // "ABC" is the letters PAGE: it replaces the other pads in
                            // every window size.
                            if (next) {
                                showSymbols = false
                                showNumbers = false
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The strip between the pty canvas and the keyboard: the GNU Screen history toggle
 * on the leading edge, the caption in whatever space the controls leave over, and
 * the live status on the trailing edge - a quiet dot + "Live" at the prompt, a
 * one-tap pill as soon as the user has scrolled away from it.
 *
 * It is part of the layout, NOT an overlay. Floating these controls over the canvas
 * is what produced the reported overlap: chip and pill were anchored to opposite
 * corners and landed on each other once the labels got wider (Spanish), the caption
 * ran under the pill, and in a window shorter than the row itself the whole row was
 * drawn on top of the tab strip - a Box does not clip its children. In flow none of
 * that can happen: the canvas simply keeps the rows that are left.
 *
 * The strip is present for every ACTIVE session rather than only when there is
 * something to show, because appearing and disappearing on scroll would resize the
 * pty on every gesture. A Short window gets the compact variant (icon-only controls,
 * tighter padding) so the canvas keeps as many rows as possible.
 */
@Composable
private fun TerminalStatusStrip(
    modifier: Modifier = Modifier,
    showHistory: Boolean,
    historyActive: Boolean,
    historyIconOnly: Boolean,
    showHint: Boolean,
    onToggleHistory: () -> Unit,
    atLive: Boolean,
    liveIconOnly: Boolean,
    onBackToLive: () -> Unit,
) {
    val rowPadding = if (liveIconOnly) {
        TerminalOverlayLayout.ROW_PADDING_COMPACT_DP.dp
    } else {
        TerminalOverlayLayout.ROW_PADDING_DP.dp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showHistory) {
            HistoryModeChip(
                active = historyActive,
                iconOnly = historyIconOnly,
                onToggle = onToggleHistory,
            )
        }
        if (historyActive && showHint) {
            // Takes only what the controls leave over (weight), so it can never
            // slide under them; with no room left the layout rule hides it.
            TerminalCanvasTheme {
                Text(
                    text = stringResource(R.string.terminal_history_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Same slot either way, so the strip never changes height when the user
        // scrolls - which would resize the pty under their fingers.
        if (atLive) {
            LiveStatus()
        } else {
            BackToLivePill(iconOnly = liveIconOnly, onClick = onBackToLive)
        }
    }
}

/**
 * The quiet half of the live slot: "you are looking at the prompt". It costs
 * nothing to read and keeps the strip exactly as tall as the pill that replaces it
 * once the user scrolls back.
 */
@Composable
private fun LiveStatus() {
    val liveLabel = stringResource(R.string.terminal_live)
    TerminalCanvasTheme {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics { contentDescription = liveLabel },
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = liveLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One-tap way back to the live prompt while the user is reading scrollback.
 *
 * Finger scrolling no longer snaps back to the bottom when the remote sends output
 * (that made scrolling useless during streaming output), so the UI has to say where
 * the viewport is and offer a way home. It owns the trailing edge of the status
 * strip, clear of the one-handed thumb reach on tablets and portrait phones. In the
 * compact strip (Short window) it keeps only the arrow: the label is the wide part,
 * and the pill already carries its meaning as an accessibility description.
 */
@Composable
private fun BackToLivePill(
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
    onClick: () -> Unit,
) {
    val liveDesc = stringResource(R.string.terminal_live_desc)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .semantics { contentDescription = liveDesc }
            // 14 dp vertical padding over the 20 dp label line puts the whole pill at
            // the 48 dp M3 minimum for a standalone touch target; the icon-only
            // variant keeps 11 dp over the 18 dp arrow, i.e. 40 dp.
            .padding(
                horizontal = if (iconOnly) 12.dp else 16.dp,
                vertical = if (iconOnly) 11.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!iconOnly) {
            Text(
                text = stringResource(R.string.terminal_live),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                // Takes only what the pill's slot leaves over, so a long translation
                // (or a large font scale) shrinks the label instead of pushing the
                // pill past the end of the strip.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * The GNU Screen "history" toggle.
 *
 * Attaching through GNU Screen means the scrollback the user cares about lives on the
 * server (screen repaints the window with absolute positioning, so the emulator's own
 * transcript starts empty and pre-attach output is unreachable locally). This chip
 * drives screen's copy mode: while it is on, finger drags, the Scroll keys and
 * PGUP/PGDN page screen's history, and any typed key leaves it again.
 *
 * An M3 FilterChip (a two-state filter with a check when selected), wrapped in the
 * dark role set because it floats on the black pty canvas, where light-scheme tokens
 * would not hold contrast. In a Short window it drops the label and keeps the icon
 * (see [TerminalOverlayLayout.compactStrip]), so the state is carried by the
 * icon and the chip's own selected styling - and the label moves to accessibility,
 * which is the only place it can still be read.
 */
@Composable
private fun HistoryModeChip(active: Boolean, iconOnly: Boolean, onToggle: () -> Unit) {
    val historyLabel = stringResource(
        if (active) R.string.terminal_history_on else R.string.terminal_history_off
    )
    TerminalCanvasTheme {
        FilterChip(
            selected = active,
            onClick = onToggle,
            modifier = if (iconOnly) {
                Modifier.semantics { contentDescription = historyLabel }
            } else {
                Modifier
            },
            label = {
                if (iconOnly) {
                    Spacer(Modifier.width(0.dp))
                } else {
                    // One line, always: a wrapped label would grow the strip and
                    // take rows off the canvas. When the label cannot fit the
                    // layout rule above drops it and the chip goes icon-only.
                    Text(historyLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leadingIcon = {
                Icon(
                    imageVector = if (active) Icons.Outlined.Check else Icons.Outlined.History,
                    // The chip itself carries the label + selected state in its
                    // merged semantics, so the icon stays decorative (no double talk-back).
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
    }
}

/** A reusable status/error card for non-Active tabs. */
@Composable
private fun StatusPanel(
    icon: (@Composable () -> Unit)?,
    title: String,
    message: String,
    action: @Composable () -> Unit,
    retryAction: (() -> Unit)? = null,
) {
    // Painted on the black pty canvas: scope the dark role set here so the text,
    // icons and buttons come from Material tokens instead of hard-coded white.
    TerminalCanvasTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.height(18.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            if (retryAction != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = retryAction) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                    action()
                }
            } else {
                action()
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, label: String) {
    // Inherits the TextButton role (primary) from the surrounding
    // TerminalCanvasTheme, which guarantees readable contrast on the canvas.
    TextButton(onClick = onClick) {
        Text(label)
    }
}

/**
 * Opens this app's system settings page.
 *
 * Only reachable when RECORD_AUDIO was refused for good: at that point no dialog the
 * app can raise will ever appear again, and the one thing that still works is the
 * system's own permission switch.
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/** Horizontally scrollable strip of session tabs + an "add another" button. */
@Composable
private fun TerminalTabStrip(
    tabs: List<LiveTerminalStore.Tab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TerminalTabChip(
                label = tab.label,
                state = tab.state,
                selected = tab.id == activeId,
                onClick = { onSelect(tab.id) },
                onClose = { onClose(tab.id) },
            )
        }
        // "+" opens the connection screen so another session can be added as a tab.
        Box(
            modifier = Modifier
                // 36dp hit area (M3 standalone guidance is 48dp; the strip is a
                // dense inline group, so this stays over the 24dp WCAG floor while
                // keeping the strip one chip-row tall).
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.terminal_add_session),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TerminalTabChip(
    label: UiText,
    state: SessionState,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    // Labels are resolved at draw time, so a tab opened before a language change
    // relabels itself without the session being touched.
    val labelText = label.rememberResolved()
    // M3 chip roles: selected = secondaryContainer/onSecondaryContainer,
    // unselected = outlined (outlineVariant border) on the app surface. Using
    // "primary" as a chip fill is not a Material chip state and also breaks the
    // 3:1 contrast of the status dot.
    val scheme = MaterialTheme.colorScheme
    val background = if (selected) scheme.secondaryContainer else Color.Transparent
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    // Semantic state tones (see ui/theme/StatusColors.kt): fixed green / amber /
    // neutral so a live tab is recognisable in either scheme and under dynamic colour.
    val status = statusColors()
    val dotColor = when (state) {
        is SessionState.Active -> status.active
        is SessionState.Connecting -> status.connecting
        is SessionState.Finished -> status.idle
        is SessionState.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .then(
                if (selected) Modifier else Modifier.border(
                    BorderStroke(1.dp, scheme.outlineVariant),
                    MaterialTheme.shapes.extraSmall,
                )
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Box(
            modifier = Modifier
                // 28dp: comfortably over the 24dp WCAG 2.5.8 minimum for a control
                // inside a dense chip strip (the 48dp M3 guidance is for standalone
                // targets; a tab strip is a dense inline group).
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.terminal_close_session),
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A bar that re-opens the in-app keyboard, shown when the keyboard is hidden.
 *
 * It used to carry Scroll up/down arrows on either side of the button. They are
 * gone: a finger drag on the canvas scrolls whatever scrollback is the right one
 * (see TerminalScrollRouter), and the status strip above the bar already holds the
 * GNU Screen History chip and the Live / back-to-live control. One wide,
 * unambiguous action instead of three targets crammed into one strip.
 */
@Composable
private fun ShowKeyboardBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            // clickable() comes before the insets so the whole strip - the navigation
            // bar area included - answers the tap; 24 dp of icon plus 2 x 14 dp of
            // padding is a 52 dp target, above the M3 minimum.
            .clickable(onClick = onClick)
            .navigationBarsPadding()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Keyboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.height(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.terminal_show_keyboard),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
