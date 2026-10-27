package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sangiorgisrl.screenlink.session.LiveTerminalStore
import com.sangiorgisrl.screenlink.session.TerminalSessionService
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade for the tabbed terminal screen.
 *
 * All SSH sessions are owned by the foreground [TerminalSessionService] and exposed
 * through the app-scoped [LiveTerminalStore], so they survive backgrounding and
 * Activity destruction. This ViewModel just reads the store (tabs + active tab),
 * switches tabs, and lets the UI close tabs / disconnect everything.
 *
 * There is deliberately NO onCleared()/disconnect: destroying the Activity must not
 * kill the sessions. Explicit disconnect happens only from user actions.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    val tabs: StateFlow<List<LiveTerminalStore.Tab>> = LiveTerminalStore.tabs
    val activeId: StateFlow<String?> = LiveTerminalStore.activeId

    fun select(id: String) {
        LiveTerminalStore.setActive(id)
    }

    /** True while at least one tab has a live (or connecting) SSH session. */
    fun anyLive(): Boolean = LiveTerminalStore.tabs.value.any {
        it.state is LiveTerminalStore.SessionState.Active ||
            it.state is LiveTerminalStore.SessionState.Connecting
    }

    /** Re-run the SSH connect for a tab whose open failed or whose link closed.
     *  The failed runner already removed itself from the service, so re-starting
     *  the connection for the same tab identity is safe (no duplicate session). */
    fun retryTab(id: String) {
        val tab = LiveTerminalStore.tabOf(id) ?: return
        if (tab.state !is LiveTerminalStore.SessionState.Error &&
            tab.state !is LiveTerminalStore.SessionState.Finished
        ) {
            return
        }
        // Flip back to Connecting immediately so the UI gives feedback; the service
        // will re-run the connect for this same tab.
        LiveTerminalStore.updateTab(id, LiveTerminalStore.SessionState.Connecting)
        TerminalSessionService.startConnect(
            getApplication(),
            tabId = id,
            connectionId = tab.request.connectionId,
            mode = tab.request.mode,
            sessionName = tab.request.sessionName,
        )
    }

    /** Close one tab: disconnect its transport in the service, then drop the tab. */
    fun closeTab(id: String) {
        LiveTerminalStore.tabOf(id)?.let { t ->
            if (t.state is LiveTerminalStore.SessionState.Active ||
                t.state is LiveTerminalStore.SessionState.Connecting
            ) {
                TerminalSessionService.disconnect(getApplication(), id)
            }
        }
        LiveTerminalStore.removeTab(id)
    }

    /** Disconnect ALL live tabs (used by the exit-confirmation dialog). */
    fun disconnectAll() {
        LiveTerminalStore.tabs.value.forEach { t ->
            if (t.state is LiveTerminalStore.SessionState.Active ||
                t.state is LiveTerminalStore.SessionState.Connecting
            ) {
                TerminalSessionService.disconnect(getApplication(), t.id)
            }
        }
    }
}
