package com.sangiorgisrl.screenlink.ui.terminal

/**
 * Decides what a vertical scroll gesture should do, mirroring the mode-aware
 * behavior of the terminal emulator. Kept pure so the mode selection is unit
 * tested without an Android view.
 *
 * The rules (see also the work log):
 *  - If the app enabled mouse reporting it wants pointer events, so wheels are
 *    forwarded verbatim (mouse tracking always wins). Inside a GNU Screen window that
 *    signal is only trustworthy because this app never enables screen's own
 *    `mousetrack`: screen would then claim the mouse for every window, plain shells
 *    included (see ScreenCommands.disableMouseTrackingCommand).
 *  - Touch drags are NEVER turned into arrow keys: GNU Screen engages the
 *    alternate buffer even for a plain bash (which does not send ESC[?1007h),
 *    and arrows there recall shell history instead of scrolling. The only
 *    exception is a TUI that explicitly asked for xterm "alternate scroll"
 *    (ESC[?1007h, which a text user interface asks for explicitly), meaning it
 *    wants scrolls to become arrow keys.
 *  - A real mouse wheel (or touchpad) inside any alternate-screen app keeps the
 *    traditional xterm "alternate scroll" behavior = arrow keys, which is what
 *    pagers like less/htop rely on.
 *  - Everything else scrolls the terminal's own scrollback (transcript).
 */
enum class TerminalScrollAction {
    WHEEL_UP,
    WHEEL_DOWN,
    ARROW_UP,
    ARROW_DOWN,
    SCROLL_BACK,
    SCROLL_FORWARD,
}

object TerminalScrollRouter {

    /**
     * The route for one scroll input, following the rules on [TerminalScrollAction].
     *
     * @param mouseTrackingActive    the app enabled mouse reporting (1000/1002/1006).
     * @param alternateBufferActive  the alternate screen buffer is active (1049h).
     * @param alternateScrollActive  the app requested xterm alternate scroll (ESC[?1007h).
     * @param fromTouch              the gesture came from a finger on the touchscreen.
     * @param up                     true when the gesture/velocity means "back in time"
     *                               (finger swiped upward, or a wheel rolled up).
     */
    fun route(
        mouseTrackingActive: Boolean,
        alternateBufferActive: Boolean,
        alternateScrollActive: Boolean,
        fromTouch: Boolean,
        up: Boolean,
    ): TerminalScrollAction {
        if (mouseTrackingActive) return if (up) TerminalScrollAction.WHEEL_UP else TerminalScrollAction.WHEEL_DOWN
        if (alternateBufferActive && (!fromTouch || alternateScrollActive)) {
            return if (up) TerminalScrollAction.ARROW_UP else TerminalScrollAction.ARROW_DOWN
        }
        return if (up) TerminalScrollAction.SCROLL_BACK else TerminalScrollAction.SCROLL_FORWARD
    }

    /**
     * Whether a scroll GESTURE (finger drag, momentum fling, mouse wheel) must drive
     * GNU Screen's copy mode rather than our viewport or the remote's keys.
     *
     * Screen keeps its scrollback on the SERVER, so a drag inside one of its windows
     * has to ask screen for the rows - our own transcript starts empty at attach and
     * can never reach output produced before this client connected. Mouse reporting
     * still wins, because an app that asked for pointer events is scrolling
     * itself - and inside a Screen window that means the INNER app, never screen,
     * because the app keeps screen's own `mousetrack` switched off. A server whose
     * `.screenrc` turns it on anyway still has the History chip: [copyModeActive]
     * outranks mouse reporting.
     *
     * @param copyModeActive          copy mode is already open (stay in it, both ways).
     * @param screenCopyModeAvailable the attached session is a GNU Screen window.
     * @param mouseTrackingActive     the remote app enabled mouse reporting.
     */
    fun gestureUsesScreenCopy(
        copyModeActive: Boolean,
        screenCopyModeAvailable: Boolean,
        mouseTrackingActive: Boolean,
    ): Boolean = copyModeActive || (screenCopyModeAvailable && !mouseTrackingActive)
}
