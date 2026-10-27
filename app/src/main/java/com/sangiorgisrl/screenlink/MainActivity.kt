package com.sangiorgisrl.screenlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.navigation.navArgument
import com.sangiorgisrl.screenlink.session.RecentSessions
import com.sangiorgisrl.screenlink.session.TerminalLauncher
import com.sangiorgisrl.screenlink.transfer.ShareInbox
import com.sangiorgisrl.screenlink.i18n.AppLocale
import com.sangiorgisrl.screenlink.ui.Routes
import com.sangiorgisrl.screenlink.ui.screens.ConnectionFormScreen
import com.sangiorgisrl.screenlink.ui.screens.FilesScreen
import com.sangiorgisrl.screenlink.ui.screens.HostKeyTrustDialog
import com.sangiorgisrl.screenlink.ui.screens.HomeScreen
import com.sangiorgisrl.screenlink.ui.screens.KeyFormScreen
import com.sangiorgisrl.screenlink.ui.screens.KeysScreen
import com.sangiorgisrl.screenlink.ui.screens.OverviewScreen
import com.sangiorgisrl.screenlink.ui.screens.TerminalScreen
import com.sangiorgisrl.screenlink.ui.screens.TransfersScreen
import com.sangiorgisrl.screenlink.ui.theme.ScreenLinkTheme
import com.sangiorgisrl.screenlink.ui.viewmodel.ConnectionFormViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.FilesViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.HomeViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.KeyFormViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.KeysViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.OverviewViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.TerminalViewModel
import com.sangiorgisrl.screenlink.ui.viewmodel.TransfersViewModel

class MainActivity : ComponentActivity() {

    companion object {
        /** Set on the launch intent (e.g. by the foreground-service notification) to
         *  open the tabbed terminal screen directly so sessions are reachable from
         *  the notification. */
        const val EXTRA_OPEN_TERMINAL = "open_terminal"

        /** Set on the launch intent (by the copy-queue notification) to land on the
         *  copy queue: the screen that holds the progress rows. It is a screen of its
         *  own now - the Files screen is a folder browser, and the rows the
         *  notification promised sat at the bottom of a listing that is routinely
         *  several pages long. */
        const val EXTRA_OPEN_TRANSFERS = "open_transfers"
    }

    private val openTerminalRequests = MutableStateFlow(0)
    /** Bumped by the copy-queue notification ("tap to watch"). */
    private val openTransfersRequests = MutableStateFlow(0)
    /** Bumped by a share-sheet hand-off; the NavHost reacts by opening the Files screen. */
    private val shareRequests = MutableStateFlow(0)
    private var notificationPermissionLauncher: ActivityResultLauncher<String>? = null

    /** The activity must draw in the app's language, not only the system one. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (!handled && TerminalFocusHolder.terminalVisible) {
            // Guard against a device quirk where an UNHANDLED Enter could pop the
            // terminal screen. Compose gets first crack (super.dispatchKeyEvent): a
            // focused in-app key / tab chip / system control that consumes Enter (e.g.
            // TalkBack or a hardware keyboard activating a focused element) reports
            // handled=true and is left alone. TAB / DPAD_CENTER / arrows are NOT
            // swallowed so keyboard traversal and screen readers keep working on the
            // terminal screen.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                return true
            }
        }
        return handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        // Android 13+: ask up front for POST_NOTIFICATIONS. The foreground-service
        // notification is the ONLY way to reach live sessions from the notification
        // shade ("Tap to open" / "Disconnect"), so without this permission the whole
        // session-when-backgrounded story silently loses its control surface.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* Best-effort: the user may say no; sessions still run in the service. */ }
            notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ScreenLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    // A notification tap ("open terminal") always lands on the tabbed
                    // terminal screen, wherever the app currently is.
                    val openCount by openTerminalRequests.collectAsState()
                    LaunchedEffect(openCount) {
                        if (openCount > 0) {
                            openTerminalRequests.value = 0
                            navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                        }
                    }
                    // Same for a running copy: the notification has to get the user
                    // back to the progress rows, from anywhere, without stacking a
                    // second queue screen on top of the one they are already reading.
                    val transfersCount by openTransfersRequests.collectAsState()
                    LaunchedEffect(transfersCount) {
                        if (transfersCount > 0) {
                            openTransfersRequests.value = 0
                            if (navController.currentDestination?.route != Routes.TRANSFERS) {
                                navController.navigate(Routes.TRANSFERS) { launchSingleTop = true }
                            }
                        }
                    }
                    // A shared file always lands on the Files screen, with the
                    // connection still to be chosen (the sender does not know it).
                    val shareCount by shareRequests.collectAsState()
                    LaunchedEffect(shareCount) {
                        if (shareCount > 0) {
                            shareRequests.value = 0
                            // Already on the Files screen? Then the list below already
                            // shows the shared file: pushing another copy of the screen
                            // would hide it behind a fresh, empty one.
                            if (navController.currentDestination?.route != Routes.FILES) {
                                navController.navigate(Routes.files()) { launchSingleTop = true }
                            }
                        }
                    }
                    ScreenLinkNavHost(navController = navController)
                    // The first-contact host-key question, mounted above every screen:
                    // the connection that asks may be a terminal tab or a copy queue,
                    // and nothing is sent to that server until this is answered.
                    val app = LocalContext.current.applicationContext as ScreenLinkApp
                    HostKeyTrustDialog(app.hostKeyTrustPrompt)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The system does not update getIntent() on its own: without this, a later
        // read of getIntent() inside this instance would still see the Intent that
        // first created it, not the share or notification tap that just arrived.
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * One place for the one question every Intent asks: which screen is this for?
     *
     * The rule is pure ([LaunchRequest]) and unit-tested, because the answer used to
     * be reached by elimination - "no files in this Intent, so a share must have
     * arrived empty" - and that turned every launcher tap into a share, opening the
     * Files screen as if it were the app's home screen.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        when (
            LaunchRequest.classify(
                action = intent?.action,
                openTerminal = intent?.getBooleanExtra(EXTRA_OPEN_TERMINAL, false) == true,
                openTransfers = intent?.getBooleanExtra(EXTRA_OPEN_TRANSFERS, false) == true,
            )
        ) {
            LaunchRequest.Kind.Terminal -> openTerminalRequests.value++
            LaunchRequest.Kind.Transfers -> openTransfersRequests.value++
            LaunchRequest.Kind.Share -> acceptSharedFiles(intent)
            // A launcher tap means "open the app". The NavHost's start destination
            // (Home) is already what is on screen, so nothing is navigated at all.
            LaunchRequest.Kind.Home -> Unit
        }
    }

    /**
     * Take files another app shared into ScreenLink (ACTION_SEND / SEND_MULTIPLE).
     *
     * Only ever called for a share action: an Intent that is not a share never
     * reaches this method, so "nothing arrived" stays what it means - a share that
     * carried nothing - rather than a verdict on every launcher tap.
     *
     * The URIs are staged into the app cache on the application scope (the grant
     * from the sending app is temporary, and a copy must outlive this Activity),
     * then the Files screen is opened so the user only has to pick the server and
     * the folder - see [ShareInbox]. A shared *text* becomes a `.txt` file, and a
     * share that carried neither says so on the Files screen instead of opening an
     * empty list.
     */
    private fun acceptSharedFiles(intent: Intent?) {
        val payload = ShareInbox.collect(intent)
        val app = application as ScreenLinkApp
        if (payload.uris.isEmpty() && payload.text == null) {
            // Nothing usable arrived (a shared folder, or an empty share). The
            // screen still opens - the user tapped "Share", so an answer has to
            // appear somewhere - and the Files screen explains what was refused.
            ShareInbox.reportNothingToStage()
            shareRequests.value++
            return
        }
        app.appScope.launch {
            if (payload.uris.isNotEmpty()) {
                ShareInbox.stage(applicationContext, app.localFiles, payload.uris)
            }
            payload.text?.let { ShareInbox.stageText(applicationContext, it, payload.title) }
        }
        shareRequests.value++
    }
}

@androidx.compose.runtime.Composable
private fun ScreenLinkNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val context = LocalContext.current
            val vm: HomeViewModel = viewModel(factory = homeVmFactory)
            HomeScreen(
                viewModel = vm,
                onOpenConnection = { id -> navController.navigate(Routes.overview(id)) },
                onNewConnection = { navController.navigate(Routes.FORM_NEW) },
                onEditConnection = { id -> navController.navigate(Routes.formEdit(id)) },
                onManageKeys = { navController.navigate(Routes.KEYS) },
                onOpenRecent = { id, mode, session ->
                    TerminalLauncher.open(context.applicationContext, id, mode, session)
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onRemoveRecent = { id, mode, session ->
                    RecentSessions.remove(context.applicationContext, id, mode, session)
                },
                onOpenTerminal = {
                    // Re-enter the shared terminal screen with all existing live tabs.
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) { launchSingleTop = true } },
            )
        }

        composable(Routes.KEYS) {
            val vm: KeysViewModel = viewModel(factory = keysVmFactory)
            KeysScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onAddKey = { navController.navigate(Routes.KEY_ADD) },
            )
        }

        composable(Routes.KEY_ADD) {
            val vm: KeyFormViewModel = viewModel(factory = keyFormVmFactory)
            KeyFormScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = {
                    navController.popBackStack() // -> keys list
                },
            )
        }

        composable(Routes.FORM_NEW) {
            val vm: ConnectionFormViewModel = viewModel(factory = formVmFactory(0L))
            ConnectionFormScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.FORM_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            val vm: ConnectionFormViewModel = viewModel(
                key = "edit-$id",
                factory = formVmFactory(id),
            )
            ConnectionFormScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.OVERVIEW,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            val context = LocalContext.current
            val vm: OverviewViewModel = viewModel(
                key = "overview-$id",
                factory = overviewVmFactory(id),
            )
            OverviewScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenTerminal = { mode, session ->
                    // Add the session as a NEW tab (multiple terminal sessions can run
                    // at once) and go to the shared tabbed terminal screen.
                    TerminalLauncher.open(context.applicationContext, id, mode, session)
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onEdit = { navController.navigate(Routes.formEdit(id)) },
                onOpenFiles = { navController.navigate(Routes.files(id)) },
            )
        }

        composable(
            route = Routes.FILES,
            arguments = listOf(
                navArgument("connectionId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("connectionId") ?: -1L
            val vm: FilesViewModel = viewModel(
                key = "files-$id",
                factory = filesVmFactory(id),
            )
            FilesScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onAddConnection = { navController.navigate(Routes.FORM_NEW) },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) { launchSingleTop = true } },
            )
        }

        composable(Routes.TRANSFERS) {
            val vm: TransfersViewModel = viewModel(
                key = "transfers-screen",
                factory = transfersVmFactory,
            )
            TransfersScreen(
                jobs = vm.jobs.collectAsStateWithLifecycle().value,
                messages = vm.messages,
                onBack = { navController.popBackStack() },
                onCancel = vm::cancel,
                onRetry = vm::retry,
                onCancelAll = vm::cancelAll,
                onClearFinished = vm::clearFinished,
            )
        }

        composable(route = Routes.TERMINAL) {
            val vm: TerminalViewModel = viewModel(
                key = "terminal-screen",
                factory = terminalVmFactory,
            )
            TerminalScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

// --- factories ---------------------------------------------------------------

private val homeVmFactory = viewModelFactory {
    initializer { HomeViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!) }
}

private fun formVmFactory(id: Long) = viewModelFactory {
    initializer {
        ConnectionFormViewModel(
            this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!,
            id,
        )
    }
}

private val keysVmFactory = viewModelFactory {
    initializer { KeysViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!) }
}

private val keyFormVmFactory = viewModelFactory {
    initializer { KeyFormViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!) }
}

private fun overviewVmFactory(id: Long) = viewModelFactory {
    initializer {
        OverviewViewModel(
            this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!,
            id,
        )
    }
}

private fun filesVmFactory(id: Long) = viewModelFactory {
    initializer {
        FilesViewModel(
            this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!,
            id,
        )
    }
}

private val terminalVmFactory = viewModelFactory {
    initializer {
        TerminalViewModel(
            this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!,
        )
    }
}

private val transfersVmFactory = viewModelFactory {
    initializer {
        TransfersViewModel(
            this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY]!!,
        )
    }
}
