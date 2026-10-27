package com.sangiorgisrl.screenlink

/**
 * Tracks whether the terminal screen is currently visible so MainActivity can make
 * sure stray Enter/Tab key events are never turned into back-navigation (an Android
 * quirk when the terminal view momentarily lacks focus).
 */
object TerminalFocusHolder {
    @Volatile
    var terminalVisible: Boolean = false
}
