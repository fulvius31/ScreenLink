package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.transfer.TransferStatus
import com.sangiorgisrl.screenlink.transfer.TransferStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow

/**
 * The copy queue on its own screen.
 *
 * Deliberately thin: the queue is application-scoped (`TransferStore`), so this view
 * model only mirrors it, hands the four actions over, and reports what they did.
 * Closing the screen - or being killed with the app in the background - changes
 * nothing about what is copying; the notification is what keeps the process alive.
 */
class TransfersViewModel(application: Application) : AndroidViewModel(application) {

    private val transfers = (application as ScreenLinkApp).transfers

    /**
     * Every queued / running / finished copy, straight from the app-scoped queue.
     *
     * No `stateIn` wrapper: the queue is already a hot StateFlow, and a
     * `stateIn` share opens every subscription on the `emptyList()` it was given - the
     * screen would open on "No copies yet" for a frame while a file is still copying.
     * See the note on [TransferStore.jobs].
     */
    val jobs: StateFlow<List<TransferStore.Job>> = transfers.jobs

    /** One-shot confirmations, resolved to text by the screen that shows them. */
    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Channel<UiText> = _messages

    fun cancel(jobId: String) {
        transfers.cancel(jobId)
        _messages.trySend(UiText.Resource(R.string.files_transfer_cancelled))
    }

    /**
     * Stop every copy the queue still owns.
     *
     * A batch queued against the wrong server (or the wrong folder) is otherwise ten
     * taps on ten rows, which is not a fair thing to ask of someone watching a list
     * they do not want.
     */
    fun cancelAll() {
        val active = transfers.jobs.value.count {
            it.status == TransferStatus.Queued || it.status == TransferStatus.Running
        }
        if (active == 0) return
        transfers.cancelAll()
        _messages.trySend(UiText.Resource(R.string.files_transfers_cancelled_all))
    }

    /**
     * Run one copy again.
     *
     * [TransferStore.retry] answers null when that row is no longer in the queue (the
     * user cleared it, or another window of the app did), and silence there reads as
     * "it started again" while nothing is copying.
     */
    fun retry(jobId: String) {
        val group = transfers.retry(jobId)
        if (group == null) {
            _messages.trySend(UiText.Resource(R.string.err_transfer_not_found))
        }
    }

    fun clearFinished() { transfers.clearFinished() }
}
