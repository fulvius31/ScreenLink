package com.sangiorgisrl.screenlink.session

import android.content.Context
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ScreenLinkApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opens a new terminal session into the app-scoped store and (if needed) starts
 * the foreground session service that owns its SSH connection. Every open adds a
 * NEW tab - multiple sessions can run side by side - and records the session in
 * [RecentSessions] so it shows up pinned on the Home screen. Returns the new tab id.
 */
object TerminalLauncher {

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun open(context: Context, connectionId: Long, mode: String, sessionName: String?): String {
        val request = LiveTerminalStore.TabRequest(
            id = LiveTerminalStore.newId(),
            connectionId = connectionId,
            mode = mode,
            sessionName = sessionName,
        )
        LiveTerminalStore.addTab(request)
        TerminalSessionService.startConnect(
            context.applicationContext,
            tabId = request.id,
            connectionId = connectionId,
            mode = mode,
            sessionName = sessionName,
        )
        recordRecent(context.applicationContext, connectionId, mode, sessionName)
        return request.id
    }

    /** Best-effort, async: remember this session for the Home screen's pinned list. */
    private fun recordRecent(context: Context, connectionId: Long, mode: String, sessionName: String?) {
        recordScope.launch {
            val app = context as? ScreenLinkApp
            val display = runCatching { app?.repository?.get(connectionId)?.displayName }.getOrNull()
            val base = if (!display.isNullOrBlank()) {
                display
            } else {
                context.getString(R.string.connection_fallback, connectionId)
            }
            // Persisted as plain text: it is a snapshot of what the session was called
            // at the time, alongside user-typed session names, so it is not re-translated.
            val label = if (mode == "shell") {
                base
            } else {
                "$base · ${sessionName ?: context.getString(R.string.tab_screen)}"
            }
            RecentSessions.record(
                context,
                RecentSessions.RecentSession(
                    connectionId = connectionId,
                    mode = mode,
                    sessionName = sessionName,
                    label = label,
                    at = System.currentTimeMillis(),
                ),
            )
        }
    }
}
