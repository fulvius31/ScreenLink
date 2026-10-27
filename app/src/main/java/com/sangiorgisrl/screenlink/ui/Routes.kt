package com.sangiorgisrl.screenlink.ui

object Routes {
    const val HOME = "home"
    const val KEYS = "keys"
    const val KEY_ADD = "keys/add"
    const val FORM_NEW = "connection/new"
    const val FORM_EDIT = "connection/{id}/edit"
    const val OVERVIEW = "connection/{id}"
    // One shared, tabbed terminal screen. Sessions are opened into the app-scoped
    // LiveTerminalStore by TerminalLauncher, then this screen renders them as tabs.
    const val TERMINAL = "terminal"
    // File transfer ("scp"). The connection id is optional: -1 means the user picks
    // the connection inside the screen, which is what a share-sheet hand-off needs
    // (the shared file does not know which server it is meant for).
    const val FILES = "files?connectionId={connectionId}"
    // The copy queue on a screen of its own. It is app-wide (the queue lives in the
    // application), so it takes no connection id and is reachable from the Files
    // screen's top bar, from Home and from the copy notification.
    const val TRANSFERS = "transfers"

    fun formEdit(id: Long): String = "connection/$id/edit"

    fun overview(id: Long): String = "connection/$id"

    fun files(id: Long = -1L): String = "files?connectionId=$id"
}
