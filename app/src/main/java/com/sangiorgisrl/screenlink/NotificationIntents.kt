package com.sangiorgisrl.screenlink

/**
 * The request code of every PendingIntent this app hands to the notification shade.
 *
 * A PendingIntent is found again by (kind, request code, `Intent.filterEquals`) - and
 * `filterEquals` compares the action, the data, the type, the component, the
 * categories and the flags. It does **not** compare extras. So two notifications that
 * both ask for `getActivity(context, 0, Intent(this, MainActivity::class.java))` are
 * the same token: the second call returns the first one, extras included, and
 * `FLAG_IMMUTABLE` (which the platform requires) makes it impossible to correct
 * afterwards.
 *
 * That is exactly how "tap the copy notification" came to open the terminal screen:
 * both foreground services asked for code 0 on an Intent whose only difference was
 * the extra inside it, and whichever service painted its notification first owned the
 * other one's tap. The codes live in one place so the collision is visible - and
 * testable - instead of being two bare integers in two unrelated files.
 */
object NotificationIntents {

    /** Tap the terminal notification: open the tabbed terminal screen. */
    const val OPEN_TERMINAL: Int = 0

    /**
     * Tap the copy notification: open the copy queue.
     *
     * It has to differ from [OPEN_TERMINAL] even though both Intents point at
     * [MainActivity]: see the class note.
     */
    const val OPEN_TRANSFERS: Int = 1

    /** The "Disconnect" action on the terminal notification. */
    const val TERMINAL_DISCONNECT_ALL: Int = 2

    /** The "Stop all" action on the copy notification. */
    const val TRANSFERS_CANCEL_ALL: Int = 3
}
