package io.bluewallet.blueberry

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
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
data class CoinsRoute(
    val picker: Boolean = false,
)

@Serializable
data class TxDetailsRoute(
    val txid: String,
)

@Serializable
data object SettingsRoute

@Serializable
data object SecretRoute

@Serializable
data object AboutRoute

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
    var sendSelectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sendForm by remember { mutableStateOf(SendFormDraft()) }
    var sendFeeRateSatPerVb by remember { mutableStateOf<Double?>(null) }
    // Subscribe so recomposition runs when the stack changes (for LocalHomeVisible).
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Any pushed destination means home is covered — drop home UIKit overlays / dock.
    val homeVisible = backStackEntry != null && navController.previousBackStackEntry == null
    val goBack: () -> Unit = remember(navController) { { navController.popBackStack() } }

    CompositionLocalProvider(LocalHomeVisible provides homeVisible) {
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
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenReceive = { navController.navigate(ReceiveRoute) },
                    onOpenSend = { navController.navigate(SendRoute) },
                    onOpenCoins = { navController.navigate(CoinsRoute(picker = false)) },
                    onOpenTx = { navController.navigate(TxDetailsRoute(txid = it)) },
                    onDetailedSyncChange = { value ->
                        scope.launch(Dispatchers.Default) { saveHomeDetailedSync(db, value) }
                    },
                )
            }
            composable<ReceiveRoute> {
                ReceiveScreen(
                    runtime = runtime,
                    db = db,
                    onBack = goBack,
                )
            }
            composable<SendRoute> {
                SendScreen(
                    runtime = runtime,
                    db = db,
                    onBack = goBack,
                    initialSelectedKeys = sendSelectedKeys,
                    initialForm = sendForm,
                    onChooseCoins = { navController.navigate(CoinsRoute(picker = true)) },
                    onBroadcastSuccess = {
                        sendSelectedKeys = emptySet()
                        sendForm = SendFormDraft()
                    },
                    onSelectedKeysChange = { sendSelectedKeys = it },
                    onFormChange = { sendForm = it },
                    onFeeRateChange = { sendFeeRateSatPerVb = it },
                )
            }
            composable<CoinsRoute> { entry ->
                val route = entry.toRoute<CoinsRoute>()
                CoinsScreen(
                    runtime = runtime,
                    db = db,
                    onBack = goBack,
                    mode =
                        if (route.picker) {
                            CoinControlMode.Picker
                        } else {
                            CoinControlMode.Standalone
                        },
                    initialSelectedKeys = sendSelectedKeys,
                    feeRateSatPerVb = sendFeeRateSatPerVb,
                    onSendWithCoins = { keys ->
                        sendSelectedKeys = keys
                        navController.navigate(SendRoute) {
                            popUpTo(HomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onConfirmSelection = { keys ->
                        sendSelectedKeys = keys
                        goBack()
                    },
                    onFrozenKey = { key -> sendSelectedKeys = sendSelectedKeys - key },
                )
            }
            composable<TxDetailsRoute> { entry ->
                val route = entry.toRoute<TxDetailsRoute>()
                TxDetailsScreen(
                    runtime = runtime,
                    db = db,
                    txid = route.txid,
                    onBack = goBack,
                )
            }
            composable<SettingsRoute> {
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
                    onBack = goBack,
                    onOpenSecret = { navController.navigate(SecretRoute) },
                    onOpenAbout = { navController.navigate(AboutRoute) },
                )
            }
            composable<SecretRoute> {
                val secret =
                    remember(databasePath, session) {
                        (inspectWalletSecret(db) as? WalletSecretInspection.Ok)?.value
                    }
                SecretScreen(secret = secret, onBack = goBack)
            }
            composable<AboutRoute> {
                AboutScreen(onBack = goBack)
            }
        }
    }
}
