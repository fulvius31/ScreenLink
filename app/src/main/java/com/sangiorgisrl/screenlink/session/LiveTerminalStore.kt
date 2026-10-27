package com.sangiorgisrl.screenlink.session

import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.ssh.SshTermSession
import com.sangiorgisrl.screenlink.ssh.ScreenCommands
import com.sangiorgisrl.screenlink.ssh.buildTerminalScreenCommand
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-scoped source of truth for the interactive SSH terminal sessions
 * (multiple sessions at once, shown as tabs).
 *
 * Connections are owned by the foreground [TerminalSessionService] - NOT by any
 * Activity or ViewModel - so every session keeps running (SSH channel + keepalive)
 * while the app is backgrounded or the Activity is destroyed. The UI observes this
 * store and attaches its TerminalView to whichever session is currently active.
 */
object LiveTerminalStore {

    /** Identity + how to open a session (connection + GNU Screen attach args). */
    data class TabRequest(
        val id: String,
        val connectionId: Long,
        val mode: String,          // "shell" | "attach" | "create"
        val sessionName: String?,
    ) {
        val initialCommand: String? get() = buildTerminalScreenCommand(mode, sessionName)

        /**
         * Display label. GNU Screen sessions show the screen session name (e.g. "work"
         * for socket "1234.work"); plain shells fall back to a generic "SSH shell".
         * Refined to include the server/host once the connection is loaded (shells).
         *
         * A [UiText] so a tab opened in one language is still relabelled correctly
         * after the user switches language (the store outlives the Activity).
         */
        val defaultLabel: UiText = when (mode) {
            "shell" -> UiText.Resource(R.string.home_mode_shell)
            else -> sessionName?.takeIf { it.isNotBlank() }
                ?.let { UiText.Literal(ScreenCommands.friendlyScreenName(it)) }
                ?: UiText.Resource(R.string.tab_screen)
        }
    }

    /** Per-session transport state. */
    sealed interface SessionState {
        data object Connecting : SessionState
        data class Active(val session: SshTermSession) : SessionState
        data class Finished(val reason: UiText) : SessionState
        data class Error(val message: UiText) : SessionState
    }

    /**
     * One tab: identity + current transport state + resolved display label.
     *
     * [label] is immutable and owned by the Tab, not the request: renaming a tab
     * (once the connection's display name / host is known) replaces the Tab with
     * a copy instead of mutating a request field, so the store can never race a
     * recomposition on a half-written label.
     */
    data class Tab(val request: TabRequest, val state: SessionState, val label: UiText) {
        val id: String get() = request.id
    }

    private val counter = AtomicInteger(0)
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun newId(): String = "s${counter.incrementAndGet()}-${System.currentTimeMillis()}"

    /** Register a new tab (starts Connecting) and make it the active one. */
    fun addTab(request: TabRequest): Tab {
        val tab = Tab(request, SessionState.Connecting, request.defaultLabel)
        _tabs.value = _tabs.value + tab
        _activeId.value = tab.id
        return tab
    }

    /** Update the transport state of an existing tab (no-op if it was already closed). */
    fun updateTab(id: String, state: SessionState) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(state = state) else it }
    }

    /** Improve a tab's label once the connection's display name / host is known. */
    fun renameTab(id: String, label: UiText) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(label = label) else it }
    }

    /** Remove a tab; activates a neighbour if the removed one was active. */
    fun removeTab(id: String) {
        val updated = _tabs.value.filterNot { it.id == id }
        _tabs.value = updated
        if (_activeId.value == id) {
            _activeId.value = updated.lastOrNull()?.id ?: updated.firstOrNull()?.id
        }
    }

    fun setActive(id: String) {
        if (_tabs.value.any { it.id == id }) _activeId.value = id
    }

    fun tabOf(id: String): Tab? = _tabs.value.firstOrNull { it.id == id }

    fun reset() {
        _tabs.value = emptyList()
        _activeId.value = null
    }
}
