/**
 * The on-screen terminal view: rendering, touch/gesture handling and input connection.
 *
 * <p>Application code, in the app's own namespace. It renders the screen produced by
 * {@link com.sangiorgisrl.screenlink.terminal.emulator.TerminalEmulator} and forwards
 * keystrokes, paste, zoom and selection back to it.
 *
 * <h2>Provenance and licence</h2>
 * <p>Derived from the {@code terminal-view} module of the
 * <a href="https://github.com/termux/termux-app">Termux terminal emulator</a>
 * (tag {@code v0.118.0}), Copyright (c) the Termux contributors, Apache License 2.0;
 * see {@code LICENSE} and {@code NOTICE} at the root of this repository. Comments in
 * these files cite "upstream issue #N" - that is an issue of the project named above.
 *
 * <p>Modifications made for this app: every class here has been rewritten in Kotlin
 * (no Java source is left apart from this file); the view attaches to a generic
 * {@link com.sangiorgisrl.screenlink.terminal.emulator.TermSession} transport (repaint
 * is driven by an output listener instead of a local subprocess), font metrics and
 * palette come from the app's Material theme, and scroll gestures are routed through
 * {@link com.sangiorgisrl.screenlink.ui.terminal} so GNU Screen history and local
 * scrollback each get the gesture that fits them.
 */
package com.sangiorgisrl.screenlink.terminal.view;
