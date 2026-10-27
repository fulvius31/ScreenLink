package com.sangiorgisrl.screenlink.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic session-state colours - the M3 "extended colors" pattern.
 *
 * The M3 core roles (primary / error / tertiary ...) carry no "live / connecting /
 * idle" meaning, and dynamic colour (Android 12+) would make a *running* session
 * change hue with the user's wallpaper. Session state has to stay recognisable,
 * so these three tones are fixed per scheme, taken from the Material tonal
 * palette (green tone 40/80, amber tone 40/80, neutral outline) and pinned at
 * >= 3:1 against every surface they are painted on - the WCAG 2.5.8 / M3
 * non-text contrast floor, asserted in StatusColorsTest.
 */
@Immutable
data class StatusColors(
    /** Session attached and usable. */
    val active: Color,
    /** Handshake in progress. */
    val connecting: Color,
    /** Detached / finished / not connected. */
    val idle: Color,
)

internal val LightStatusColors = StatusColors(
    active = Color(0xFF176B2E),      // green 40
    connecting = Color(0xFF7A5900),  // amber 40
    idle = Color(0xFF75777F),        // neutral outline
)

internal val DarkStatusColors = StatusColors(
    active = Color(0xFF6DD58C),      // green 80
    connecting = Color(0xFFFFC96B),  // amber 80
    idle = Color(0xFF9A9AA4),        // neutral outline 60
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Status tones of the active theme (also correct under dynamic colour). */
@Composable
fun statusColors(): StatusColors = LocalStatusColors.current
