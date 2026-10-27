package com.sangiorgisrl.screenlink.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.MainActivity
import com.sangiorgisrl.screenlink.NotificationIntents
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.ssh.ScreenCommands
import com.sangiorgisrl.screenlink.ssh.SshTermSession
import com.sangiorgisrl.screenlink.ssh.friendlySshError
import com.sangiorgisrl.screenlink.i18n.AppLocale
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.i18n.resolve
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that OWNS the interactive SSH terminal sessions (one
 * [SessionRunner] per tab).
 *
 * Why a service: the user wants terminal sessions to survive backgrounding. If the
 * connections lived in a ViewModel, destroying the Activity (or removing the app
 * from recents) would call onCleared and tear them down. Running here - foreground
 * with a `dataSync` type - means each session's SSH channel + reader/writer threads
 * keep running while the app is backgrounded, and returning to the terminal screen
 * re-attaches the same live [SshTermSession] objects.
 *
 * The service keeps running until the last tab is disconnected or its remote side
 * closes.
 *
 * NOTE (Android 15+, targetSdk 35): dataSync foreground services are capped at
 * ~6h per 24h by the platform. The service handles the cap deterministically in
 * [onTimeout] (graceful stop of every session + self-stop), so a genuinely
 * long-lived backgrounded session ends cleanly instead of ANRing. Interactive
 * terminal work rarely approaches this; treating it as the platform's explicit
 * 'your tunnel is too old, reconnect' signal is the intended design.
 */
// FOREGROUND_SERVICE_TYPE_DATA_SYNC is a compile-time inlined int; ServiceCompat
// applies it only on API 29+, so it's safe at minSdk 26.
@android.annotation.SuppressLint("InlinedApi")
class TerminalSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenlink_terminal"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.sangiorgisrl.screenlink.action.CONNECT"
        const val ACTION_DISCONNECT = "com.sangiorgisrl.screenlink.action.DISCONNECT"
        const val EXTRA_TAB_ID = "tabId"
        const val EXTRA_CONNECTION_ID = "connectionId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SESSION_NAME = "sessionName"

        fun startConnect(
            context: Context,
            tabId: String,
            connectionId: Long,
            mode: String,
            sessionName: String?,
        ) {
            val intent = Intent(context, TerminalSessionService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_TAB_ID, tabId)
                putExtra(EXTRA_CONNECTION_ID, connectionId)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_SESSION_NAME, sessionName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context, tabId: String) {
            context.startService(
                Intent(context, TerminalSessionService::class.java).apply {
                    action = ACTION_DISCONNECT
                    putExtra(EXTRA_TAB_ID, tabId)
                }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runners = ConcurrentHashMap<String, SessionRunner>()

    /**
     * A Service gets its own ContextImpl, so it does NOT inherit the language the
     * Application wrapped: without this the notification would stay in the system
     * language on Android 8.1-12.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                val tabId = intent.getStringExtra(EXTRA_TAB_ID)
                if (tabId != null) {
                    runners.remove(tabId)?.shutdown()
                } else {
                    runners.values.forEach { it.shutdown() }
                    runners.clear()
                }
                maybeStop()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                val tabId = intent.getStringExtra(EXTRA_TAB_ID) ?: return START_NOT_STICKY
                // Re-entry for an already-running tab is a no-op (e.g. returning to the
                // terminal screen after backgrounding - do NOT reconnect).
                if (runners.containsKey(tabId)) return START_NOT_STICKY
                if (runners.isEmpty()) {
                    startForegroundWith(getString(R.string.app_name), getString(R.string.terminal_opening))
                }
                val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, 0L)
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "shell"
                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME)
                val runner = SessionRunner(tabId, connectionId, mode, sessionName) {
                    runners.remove(tabId)
                    maybeStop()
                }
                runners[tabId] = runner
                refreshNotification()
                scope.launch { runner.run() }
            }
        }
        // Never restart automatically: a killed process means the sessions were really
        // lost; silently restarting tunnels with stale state would be worse.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runners.values.forEach { it.shutdown() }
        runners.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun maybeStop() {
        if (runners.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Android 15+ (targetSdk 35): invoked when the platform enforces the dataSync
     * foreground-service timeout (~6h per 24h). The sockets/reader threads otherwise
     * keep running with no way out, and failing to stop cleanly after onTimeout ends
     * in an ANR. So: disconnect every live runner and stop the service. Sessions are
     * reconnectable from Home; this is the platform saying the tunnel is too old.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        runners.values.forEach { it.shutdown() }
        runners.clear()
        maybeStop()
    }


    private fun refreshNotification() {
        val count = runners.size
        startForegroundIfNeeded()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(
                resources.getQuantityString(R.plurals.notif_running, count, count),
                getString(R.string.notif_tap_to_open),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun startForegroundIfNeeded() {
        // No-op guard: startForeground() may be called repeatedly to update the text.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.app_name),
                getString(R.string.notif_connecting),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun startForegroundWith(title: String, line: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, line),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(title: String, line: String): Notification {
        // Tapping the notification opens the app straight into the tabbed terminal
        // screen, so the live sessions are always reachable from it.
        val contentIntent = PendingIntent.getActivity(
            // The code is NOT interchangeable with the copy service's content intent:
            // both Intents point at MainActivity and differ only by their extra, which
            // `filterEquals` ignores. See NotificationIntents.
            this, NotificationIntents.OPEN_TERMINAL,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TERMINAL, true)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        // A quick way to close every session from the notification.
        val disconnectAll = PendingIntent.getService(
            this, NotificationIntents.TERMINAL_DISCONNECT_ALL,
            Intent(this, TerminalSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_disconnect), disconnectAll)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        // The channel label is created once, in whatever language the app was in at
        // the time: Android owns channel names afterwards (there is no public API to
        // rename one, and deleting + recreating it would throw away the user's own
        // channel settings). The notification's title/text - the part people actually
        // read - is re-resolved in the current language on every update.
        manager.createNotificationChannel(channel)
    }

    /**
     * Owns ONE SSH connection + [SshTermSession] for one tab. Runs inside this
     * service (not the Activity) so it survives backgrounding / Activity destruction.
     */
    private inner class SessionRunner(
        private val tabId: String,
        private val connectionId: Long,
        private val mode: String,
        private val sessionName: String?,
        private val onStopped: () -> Unit,
    ) {
        private val app get() = application as ScreenLinkApp
        private val repository get() = app.repository
        private val ssh get() = app.sshClient
        private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private var jschSession: Session? = null
        private var termSession: SshTermSession? = null

        /** Set the moment the user asks to disconnect this tab. */
        private val closed = AtomicBoolean(false)

        /** The connecting/running coroutine; cancelled on shutdown so an in-flight
         *  connect can't outlive a tab that was closed mid-handshake (ghost session). */
        private var runJob: Job? = null

        fun run() {
            runJob = runScope.launch {
                var openedSession: Session? = null
                var openedTerminal: SshTermSession? = null
                try {
                    val connection = repository.getSshConnection(connectionId)
                    if (connection == null) {
                        LiveTerminalStore.updateTab(
                            tabId,
                            LiveTerminalStore.SessionState.Error(
                                UiText.Resource(R.string.err_connection_missing)
                            ),
                        )
                        stopSelfAndFinish()
                        return@launch
                    }
                    coroutineContext.ensureActive()
                    LiveTerminalStore.renameTab(
                        tabId,
                        terminalTabLabel(connection.displayName.ifBlank { connection.host }, mode, sessionName),
                    )
                    LiveTerminalStore.updateTab(tabId, LiveTerminalStore.SessionState.Connecting)
                    val keys = repository.getSshKeysForConnection(connectionId)

                    // The ONE way to open a session. SshConnector owns the whole
                    // host-key policy: a changed key is refused during the handshake
                    // (before any credential leaves the device), first contact is
                    // confirmed with the user before credentials are offered, a pin
                    // saved by an older build is upgraded rather than mistaken for a
                    // man-in-the-middle, and the key the user confirmed is the key this
                    // connection is checked against. Duplicating that here is how the
                    // terminal used to drift out of sync with the rest of the app.
                    val session = app.sshConnector.connect(connection, keys)
                    openedSession = session
                    jschSession = session
                    coroutineContext.ensureActive()

                    // A sensible door size; the attached TerminalView re-sizes it shortly after.
                    val channel = ssh.openShell(session, 80, 24)
                    coroutineContext.ensureActive()

                    val initialCommand = LiveTerminalStore.tabOf(tabId)?.request?.initialCommand
                    val terminal = SshTermSession(
                        channel = channel,
                        initialCommand = initialCommand,
                        onFinished = {
                            // Posted on the main thread when the remote side closes.
                            if (LiveTerminalStore.tabOf(tabId)?.state is LiveTerminalStore.SessionState.Active ||
                                LiveTerminalStore.tabOf(tabId)?.state is LiveTerminalStore.SessionState.Connecting
                            ) {
                                LiveTerminalStore.updateTab(
                                    tabId,
                                    LiveTerminalStore.SessionState.Finished(
                                        UiText.Resource(R.string.session_closed)
                                    ),
                                )
                            }
                            stopSelfAndFinish()
                        },
                    )
                    openedTerminal = terminal
                    termSession = terminal
                    coroutineContext.ensureActive()

                    LiveTerminalStore.updateTab(tabId, LiveTerminalStore.SessionState.Active(terminal))
                    releaseScreenMouseTracking(session)
                } catch (e: CancellationException) {
                    // Tab was closed while we were still opening it (shutdown() cancelled
                    // runJob). Tear down whatever already opened, then rethrow so the
                    // coroutine ends cancelled. shutdown() is responsible for onStopped().
                    runCatching { openedTerminal?.finishIfRunning() }
                    runCatching { openedSession?.disconnect() }
                    jschSession = null
                    termSession = null
                    throw e
                } catch (e: Exception) {
                    runCatching { openedSession?.disconnect() }
                    jschSession = null
                    LiveTerminalStore.updateTab(tabId, LiveTerminalStore.SessionState.Error(friendlySshError(e)))
                    stopSelfAndFinish()
                }
            }
        }

        fun shutdown() {
            if (!closed.compareAndSet(false, true)) return
            // Abort an in-flight connect (or any run-scope work) immediately.
            runJob?.cancel()
            // Disconnect whatever the run coroutine had already opened, off the main thread.
            runScope.launch {
                runCatching {
                    termSession?.finishIfRunning()
                    jschSession?.disconnect()
                }
                termSession = null
                jschSession = null
                // The run() CancellationException path deliberately skips stopSelfAndFinish(),
                // so the final stop decision belongs here (idempotent).
                onStopped()
            }
        }

        private fun stopSelfAndFinish() {
            onStopped()
        }

        /** Tab label: plain shells show the connection; GNU Screen sessions show the
         *  screen session name (e.g. "work" for socket "1234.work"). */
        private fun terminalTabLabel(
            connectionLabel: String,
            mode: String,
            sessionName: String?,
        ): UiText {
            if (mode == "shell") return UiText.Literal(connectionLabel)
            val friendly = sessionName?.takeIf { it.isNotBlank() }?.let { ScreenCommands.friendlyScreenName(it) }
            return if (friendly.isNullOrBlank()) {
                UiText.Literal(connectionLabel)
            } else {
                UiText.Literal(friendly)
            }
        }

        /**
         * Make sure GNU Screen is not the one holding the mouse (see the work log and
         * [ScreenCommands.disableMouseTrackingCommand]).
         *
         * This used to send `mousetrack on`, which is what broke finger/trackpad scrolling
         * in every Screen session: screen then switched the outer terminal into mouse
         * reporting by itself, so the scroll router saw "the app wants pointer events"
         * even in a plain bash prompt. `off` is the safe direction - it never takes the
         * mouse away from an app that asked for it (measured), and it leaves the app's
         * own copy-mode scrolling in charge for everything else.
         *
         * Best-effort and silent: it runs on the SSH exec channel, not in the user's
         * shell, so a server without that screen option just prints nothing.
         */
        private fun releaseScreenMouseTracking(session: Session) {
            if (mode != "attach" && mode != "create") return
            val raw = sessionName ?: return
            val name = ScreenCommands.normalizeName(raw)
            if (name.isEmpty()) return
            runScope.launch {
                delay(2500)
                if (closed.get()) return@launch
                runCatching {
                    ssh.exec(session, ScreenCommands.disableMouseTrackingCommand(name), 10_000)
                }
            }
        }
    }
}
