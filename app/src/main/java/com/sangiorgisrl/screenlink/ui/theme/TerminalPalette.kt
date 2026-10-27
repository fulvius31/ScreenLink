package com.sangiorgisrl.screenlink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The terminal canvas is deliberately NOT painted with the app's M3 colour roles.
 *
 * It is the ANSI default palette of the remote shell (black canvas, white default
 * foreground) - the same contract every other terminal emulator honours. Tinting
 * it with primary/secondary/surface would silently recolour remote programs
 * (vim themes, htop, colours the remote itself emits), so Material's colour
 * roles stop at the app chrome: the tab strip, app bar, keyboard and dialogs all
 * use MaterialTheme tokens, the pty canvas below them uses these constants.
 */
object TerminalPalette {
    val background = Color.Black
    val foreground = Color.White

    /** Secondary text on the terminal canvas (supporting copy). */
    val foregroundMuted = Color.White.copy(alpha = 0.8f)
    val foregroundFaint = Color.White.copy(alpha = 0.75f)
}

/**
 * Chrome that is painted ON the black terminal canvas (status panels, spinners)
 * must use the dark role set, otherwise light-scheme tokens such as `primary`
 * or `onSurface` become unreadable against black. This scopes the app's own dark
 * palette to that area instead of hard-coding white text all over the screen.
 */
@Composable
fun TerminalCanvasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
