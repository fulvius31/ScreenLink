/**
 * The VT/ANSI terminal emulator that this app drives with SSH bytes.
 *
 * <p>This is application code: it lives in the app's own namespace, is compiled into
 * the app, and is maintained here like any other package. Nothing in it opens a local
 * pty or talks to another program - the byte transport is supplied by
 * {@link com.sangiorgisrl.screenlink.ssh.SshTermSession}.
 *
 * <h2>Provenance and licence</h2>
 * <p>The bulk of this package is derived from the {@code terminal-emulator} module of
 * the <a href="https://github.com/termux/termux-app">Termux terminal emulator</a>
 * (tag {@code v0.118.0}), Copyright (c) the Termux contributors, licensed under the
 * Apache License, Version 2.0. The licence text and the attribution notice live in the
 * {@code LICENSE} and {@code NOTICE} files at the root of this repository.
 *
 * <p>Modifications made for this app (same Apache-2.0 licence):
 * <ul>
 *   <li>the code was moved into this app's own namespace and renamed;
 *   <li>{@link TermSession} replaces the upstream pty/JNI session class, so any byte
 *       transport can feed the emulator;
 *   <li>the alternate buffer keeps a transcript of its own, so touch-scrolling inside a
 *       full-screen program (GNU Screen, vim, htop) has something to scroll;
 *   <li>{@code ESC[?1007h} (alternate scroll) plus the mouse/focus modes are tracked so
 *       the app can route wheel and touch gestures the way a terminal emulator should;
 *   <li>{@link TerminalBuffer#getSelectedText(int, int, int, int, boolean)} now honours
 *       its {@code joinBackLines} argument instead of hard-coding {@code true}, which is
 *       what {@code getTranscriptTextWithoutJoinedLines()} has always claimed to do;
 *   <li>every class in this package has been rewritten in Kotlin, so no Java source is
 *       left here apart from this file. {@link WcWidth} additionally keeps its ranges as
 *       packed {@code start, end} pairs in one {@code IntArray} each instead of an array
 *       of arrays. The port was done file by file with the JVM test suite as the safety
 *       net: the observable VT/ANSI behaviour is unchanged, and the few places where the
 *       upstream code could throw are now guarded instead.
 * </ul>
 */
package com.sangiorgisrl.screenlink.terminal.emulator;
