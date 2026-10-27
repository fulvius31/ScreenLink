package com.sangiorgisrl.screenlink.ui.terminal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The terminal's controls live in a strip in the layout, so they cannot overlap the
 * canvas, each other, or the tab strip. The only things that give way are the caption
 * and the labels, and those decisions are the pure rules under test - on the window
 * shapes the app actually has to support (portrait phone, landscape phone, tablet).
 */
class TerminalOverlayLayoutTest {

    @Test
    fun `caption shows when the strip has room beside both controls`() {
        assertTrue(TerminalOverlayLayout.showHistoryHint(widthDp = 600.0, shortHeight = false))
        assertTrue(TerminalOverlayLayout.showHistoryHint(widthDp = 800.0, shortHeight = false))
        // The breakpoint itself is inclusive.
        assertTrue(TerminalOverlayLayout.showHistoryHint(
            widthDp = TerminalOverlayLayout.MIN_WIDTH_FOR_HINT_DP,
            shortHeight = false,
        ))
    }

    @Test
    fun `caption hides on a narrow window where the controls already fill the strip`() {
        assertFalse(TerminalOverlayLayout.showHistoryHint(widthDp = 411.0, shortHeight = false))
        assertFalse(TerminalOverlayLayout.showHistoryHint(widthDp = 320.0, shortHeight = false))
    }

    @Test
    fun `caption hides in a Short window even when it is wide`() {
        // Landscape phone: the canvas is a handful of rows, so the strip stays as
        // short as possible and the caption is the first thing dropped.
        assertFalse(TerminalOverlayLayout.showHistoryHint(widthDp = 914.0, shortHeight = true))
    }

    @Test
    fun `the history chip keeps its label while the strip has room for it`() {
        // Portrait phone, default text size: chip label + live control fit.
        assertTrue(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 411.0, fontScale = 1.0f, shortHeight = false),
        )
        assertTrue(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 800.0, fontScale = 1.0f, shortHeight = false),
        )
        // The breakpoint itself is inclusive.
        assertTrue(
            TerminalOverlayLayout.showHistoryLabel(
                widthDp = TerminalOverlayLayout.MIN_WIDTH_FOR_CHIP_LABEL_DP,
                fontScale = 1.0f,
                shortHeight = false,
            ),
        )
    }

    @Test
    fun `the history chip goes icon-only before its label can wrap the strip`() {
        // A small phone window cannot carry the Spanish "Historial activo" beside
        // the live control.
        assertFalse(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 320.0, fontScale = 1.0f, shortHeight = false),
        )
        // A large system font scale eats the same room sooner: the width is checked
        // in text-dp, so a 411 dp window at 1.3x behaves like a 316 dp one.
        assertTrue(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 411.0, fontScale = 1.0f, shortHeight = false),
        )
        assertFalse(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 411.0, fontScale = 1.3f, shortHeight = false),
        )
        // Short windows are icon-only whatever the width (the canvas needs the rows).
        assertFalse(
            TerminalOverlayLayout.showHistoryLabel(widthDp = 914.0, fontScale = 1.0f, shortHeight = true),
        )
    }

    @Test
    fun `a Short window gets the compact strip and every other window the full one`() {
        assertTrue(TerminalOverlayLayout.compactStrip(shortHeight = true))
        assertFalse(TerminalOverlayLayout.compactStrip(shortHeight = false))
        assertTrue(
            TerminalOverlayLayout.stripHeightDp(shortHeight = true) <
                TerminalOverlayLayout.stripHeightDp(shortHeight = false),
        )
    }

    @Test
    fun `the strip is always tall enough for the tallest control it holds`() {
        // Full: the Live pill is 20 dp of label plus 2 x 14 dp padding.
        assertTrue(
            TerminalOverlayLayout.stripHeightDp(shortHeight = false) >=
                TerminalOverlayLayout.LIVE_PILL_HEIGHT_DP + 2 * TerminalOverlayLayout.ROW_PADDING_DP,
        )
        // Compact: the icon-only pill is the 18 dp arrow plus 2 x 11 dp padding.
        assertTrue(
            TerminalOverlayLayout.stripHeightDp(shortHeight = true) >=
                TerminalOverlayLayout.LIVE_PILL_COMPACT_HEIGHT_DP +
                2 * TerminalOverlayLayout.ROW_PADDING_COMPACT_DP,
        )
        // And it never eats a whole landscape canvas: the compact strip stays under
        // 60 dp so a Short window still keeps readable rows.
        assertTrue(TerminalOverlayLayout.stripHeightDp(shortHeight = true) <= 60.0)
    }
}
