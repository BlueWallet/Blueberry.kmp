package io.bluewallet.blueberry

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.bluewallet.blueberry.boot.formatDatabaseGigabytes
import io.bluewallet.blueberry.boot.saveHomeDetailedSync
import io.bluewallet.blueberry.boot.sqliteDatabaseBytes
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.WalletSecretInspection
import io.bluewallet.blueberry.wallet.inspectWalletSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ReceiveRoute

@Serializable
data object SendRoute

@Serializable
data object CoinsRoute

@Serializable
data class TxDetailsRoute(
    val txid: String,
)

@Serializable
data class BroadcastRoute(
    val id: String,
)

@Serializable
data object SettingsRoute

@Serializable
data object SecretRoute

@Serializable
data object AboutRoute

@Serializable
data object ChangelogRoute

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Suppress("LongMethod")
internal fun WalletNavHost(
    db: Database,
    runtime: PeersRuntime,
    databasePath: String,
    session: Int,
    onClearStorage: () -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    // Hoisted so coin control can hand its selection to Send and read Send's fee rate.
    var sendDraft by remember { mutableStateOf(SendDraft.Empty) }
    val liveSendEntries = remember { mutableSetOf<String>() }
    // Subscribe so recomposition runs when the stack changes (for LocalHomeVisible).
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Any pushed destination means home is covered — drop home UIKit overlays / dock.
    val homeVisible = backStackEntry != null && navController.previousBackStackEntry == null

    // A screen pops only itself: a second tap during its exit animation must not pop the one below.
    fun popIfCurrent(entry: NavBackStackEntry): Boolean =
        navController.currentBackStackEntry?.id == entry.id &&
            navController.previousBackStackEntry != null &&
            navController.popBackStack()

    fun backFrom(entry: NavBackStackEntry): () -> Unit = { popIfCurrent(entry) }
    val openSendFresh: () -> Unit =
        remember(navController) {
            {
                sendDraft = SendDraft.Empty
                navController.navigate(SendRoute) { launchSingleTop = true }
            }
        }

    var broadcastSessions by remember { mutableStateOf(runtime.broadcastStore.sessions()) }
    DisposableEffect(runtime.broadcastStore) {
        val off =
            runtime.broadcastStore.subscribe {
                scope.launch { broadcastSessions = runtime.broadcastStore.sessions() }
            }
        onDispose { off() }
    }
    val openBroadcast: (String) -> Unit =
        remember(navController) {
            { id -> navController.navigate(BroadcastRoute(id)) { launchSingleTop = true } }
        }

    CompositionLocalProvider(
        LocalHomeVisible provides homeVisible,
        LocalBroadcastSessions provides broadcastSessions,
        LocalOpenBroadcast provides openBroadcast,
    ) {
        // Only swallow back on home so NavHost can pop pushed screens.
        BackHandler(enabled = homeVisible) { /* idle: do not finish the activity / app */ }
        // Do not set enterTransition/exitTransition: iOS keeps the native-like push defaults.
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<HomeRoute> {
                PeersScreen(
                    db = db,
                    store = runtime.store,
                    headersStore = runtime.headersStore,
                    filtersStore = runtime.filtersStore,
                    matchingStore = runtime.matchingStore,
                    blocksStore = runtime.blocksStore,
                    walletTxsStore = runtime.walletTxsStore,
                    onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onOpenReceive = { navController.navigate(ReceiveRoute) { launchSingleTop = true } },
                    onOpenSend = openSendFresh,
                    onOpenCoins = {
                        navController.navigate(CoinsRoute) { launchSingleTop = true }
                    },
                    onOpenTx = {
                        navController.navigate(TxDetailsRoute(txid = it)) { launchSingleTop = true }
                    },
                    onDetailedSyncChange = { value ->
                        scope.launch(Dispatchers.Default) { saveHomeDetailedSync(db, value) }
                    },
                )
            }
            composable<ReceiveRoute> { entry ->
                ReceiveScreen(
                    runtime = runtime,
                    db = db,
                    onBack = backFrom(entry),
                )
            }
            composable<SendRoute> { entry ->
                // The draft selection and fee end when the Send entry is destroyed.
                LaunchedEffect(entry.id) {
                    if (!liveSendEntries.add(entry.id)) return@LaunchedEffect
                    entry.lifecycle.addObserver(
                        LifecycleEventObserver { _, event ->
                            if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
                            liveSendEntries.remove(entry.id)
                            if (liveSendEntries.isEmpty()) sendDraft = SendDraft.Empty
                        },
                    )
                }
                SendScreen(
                    runtime = runtime,
                    db = db,
                    onBack = backFrom(entry),
                    initialSelectedKeys = sendDraft.selectedKeys,
                    onSelectedKeysChange = { sendDraft = sendDraft.withSelection(it) },
                    onFeeRateChange = { sendDraft = sendDraft.withFeeRate(it) },
                )
            }
            composable<CoinsRoute> { entry ->
                CoinsScreen(
                    runtime = runtime,
                    db = db,
                    onBack = backFrom(entry),
                    initialSelectedKeys = sendDraft.selectedKeys,
                    feeRateSatPerVb = sendDraft.feeRateSatPerVb,
                    onSendWithCoins = { keys ->
                        sendDraft = SendDraft.fromStandalone(keys)
                        navController.navigate(SendRoute) {
                            popUpTo(HomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onFrozenKey = { key -> sendDraft = sendDraft.frozen(key) },
                )
            }
            composable<TxDetailsRoute> { entry ->
                val route = entry.toRoute<TxDetailsRoute>()
                TxDetailsScreen(
                    runtime = runtime,
                    db = db,
                    txid = route.txid,
                    onBack = backFrom(entry),
                )
            }
            composable<BroadcastRoute> { entry ->
                val route = entry.toRoute<BroadcastRoute>()
                BroadcastScreen(runtime = runtime, id = route.id, onClose = backFrom(entry))
            }
            composable<SettingsRoute> { entry ->
                SettingsScreen(
                    databaseSize =
                        remember(databasePath, session) {
                            formatDatabaseGigabytes(sqliteDatabaseBytes(databasePath))
                        },
                    secret =
                        remember(databasePath, session) {
                            (inspectWalletSecret(db) as? WalletSecretInspection.Ok)?.value
                        },
                    db = db,
                    onClearStorage = onClearStorage,
                    onBack = backFrom(entry),
                    onOpenSecret = { navController.navigate(SecretRoute) { launchSingleTop = true } },
                    onOpenAbout = { navController.navigate(AboutRoute) { launchSingleTop = true } },
                )
            }
            composable<SecretRoute> { entry ->
                val secret =
                    remember(databasePath, session) {
                        (inspectWalletSecret(db) as? WalletSecretInspection.Ok)?.value
                    }
                SecretScreen(secret = secret, onBack = backFrom(entry))
            }
            composable<AboutRoute> { entry ->
                AboutScreen(
                    onBack = backFrom(entry),
                    onOpenChangelog = { navController.navigate(ChangelogRoute) { launchSingleTop = true } },
                )
            }
            composable<ChangelogRoute> { entry ->
                ChangelogScreen(onBack = backFrom(entry))
            }
        }
    }
}
