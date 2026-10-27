package com.sangiorgisrl.screenlink.transfer

import android.annotation.SuppressLint
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
import com.sangiorgisrl.screenlink.MainActivity
import com.sangiorgisrl.screenlink.NotificationIntents
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.i18n.AppLocale
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that holds the process while the copy queue has work.
 *
 * Without it a copy is just a coroutine in an application scope: the system is
 * free to reclaim the process halfway through a large file, and the user finds out
 * only when a 2 GB upload turns out to be 1.2 GB. Running here with a `dataSync`
 * type keeps the SSH socket alive while the app is backgrounded, and the
 * notification is the honest receipt - how many files are still moving and how far
 * the current one got, plus a way to stop everything from the shade.
 *
 * The service owns NO transfer: the queue stays in [TransferStore] (the terminal
 * service has the same shape). It only watches the queue's StateFlow, renders it,
 * and stops itself the moment nothing is running.
 *
 * Android 15+ (targetSdk 35) caps `dataSync` services at ~6 h per 24 h; [onTimeout]
 * cancels the running copies and stops, because a transfer that outlives its own
 * service is a transfer the platform is about to kill without telling the user.
 */
// FOREGROUND_SERVICE_TYPE_DATA_SYNC is a compile-time inlined int; ServiceCompat
// applies it only on API 29+, so it is safe at minSdk 26.
@SuppressLint("InlinedApi")
class TransferProgressService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenlink_transfers"
        private const val NOTIFICATION_ID = 2

        /** Notification repaints are throttled: the queue publishes 5x a second. */
        private const val MIN_REFRESH_MS = 1_000L

        private const val ACTION_SHOW = "com.sangiorgisrl.screenlink.action.TRANSFER_SHOW"
        private const val ACTION_CANCEL_ALL = "com.sangiorgisrl.screenlink.action.TRANSFER_CANCEL_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransferProgressService::class.java).setAction(ACTION_SHOW),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastNotifyAt = 0L
    private var lastSignature = ""

    /** A Service gets its own ContextImpl, so the app language must be re-applied. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transfers = (application as ScreenLinkApp).transfers
        if (intent?.action == ACTION_CANCEL_ALL) {
            // The queue is the single source of truth, so the notification action
            // goes through it rather than keeping its own idea of what is running.
            transfers.cancelAll()
            return START_NOT_STICKY
        }
        // startForeground() must happen within seconds of the start request.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(activeOf(transfers.jobs.value)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        observe(transfers)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+ dataSync timeout: stop cleanly instead of being killed mid-copy.
     * The rows stay in the queue as cancelled, so the user can retry them.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        (application as ScreenLinkApp).transfers.cancelAll()
        stopAndDropNotification()
    }

    private fun observe(transfers: TransferStore) {
        if (observing) return
        observing = true
        scope.launch {
            transfers.jobs.collect { jobs ->
                val active = activeOf(jobs)
                if (active.isEmpty()) {
                    // Nothing left to protect: drop the notification and let go, so
                    // the app stops advertising a copy that finished minutes ago.
                    stopAndDropNotification()
                    return@collect
                }
                val signature = signatureOf(active)
                val now = System.currentTimeMillis()
                if (signature != lastSignature || now - lastNotifyAt >= MIN_REFRESH_MS) {
                    lastSignature = signature
                    lastNotifyAt = now
                    ServiceCompat.startForeground(
                        this@TransferProgressService,
                        NOTIFICATION_ID,
                        buildNotification(active),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                }
            }
        }
    }

    private fun stopAndDropNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activeOf(jobs: List<TransferStore.Job>): List<TransferStore.Job> =
        jobs.filter { it.status == TransferStatus.Queued || it.status == TransferStatus.Running }

    /** What the notification would say, so identical repaints are skipped. */
    private fun signatureOf(active: List<TransferStore.Job>): String {
        val running = active.firstOrNull { it.status == TransferStatus.Running }
        val percent = running?.let { TransferFormatting.percent(it.transferredBytes, it.totalBytes) }
        return "${active.size}|${running?.id}|$percent"
    }

    private fun buildNotification(active: List<TransferStore.Job>): Notification {
        // Sizes follow the language the notification is drawn in, not the process
        // default (the app has its own in-app language picker).
        val locale: Locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val running = active.firstOrNull { it.status == TransferStatus.Running }
        val body = when {
            running != null && running.totalBytes > 0L -> "${running.fileName} \u00b7 " +
                getString(
                    R.string.files_progress_sizes,
                    TransferFormatting.bytes(running.transferredBytes, locale),
                    TransferFormatting.bytes(running.totalBytes, locale),
                )

            running != null ->
                "${running.fileName} \u00b7 ${TransferFormatting.bytes(running.transferredBytes, locale)}"

            else -> getString(R.string.files_transfers_running_hint)
        }
        val open = PendingIntent.getActivity(
            // The code is NOT interchangeable with the terminal service's content
            // intent: both Intents point at MainActivity and differ only by their
            // extra, which `filterEquals` ignores. See NotificationIntents.
            this, NotificationIntents.OPEN_TRANSFERS,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Tap the notification -> the copy queue, the screen whose rows this
                // notification is a live summary of.
                putExtra(MainActivity.EXTRA_OPEN_TRANSFERS, true)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelAll = PendingIntent.getService(
            this, NotificationIntents.TRANSFERS_CANCEL_ALL,
            Intent(this, TransferProgressService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                resources.getQuantityString(R.plurals.notif_transfer_active, active.size, active.size),
            )
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.files_transfers_cancel_all), cancelAll)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_transfer_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        // Channel names are owned by the system once created; the title/text below
        // are what get re-translated on every update.
        manager.createNotificationChannel(channel)
    }
}
