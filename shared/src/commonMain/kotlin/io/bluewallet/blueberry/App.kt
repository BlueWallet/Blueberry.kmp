@file:OptIn(ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.AppearancePreference
import io.bluewallet.blueberry.boot.OnboardingGate
import io.bluewallet.blueberry.boot.deleteSqliteDatabaseFiles
import io.bluewallet.blueberry.boot.formatDatabaseGigabytes
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.loadAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.loadAppearance
import io.bluewallet.blueberry.boot.resolveDarkTheme
import io.bluewallet.blueberry.boot.resolveOnboardingGate
import io.bluewallet.blueberry.boot.saveAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.saveAppearance
import io.bluewallet.blueberry.boot.saveHomeDetailedSync
import io.bluewallet.blueberry.boot.sqliteDatabaseBytes
import io.bluewallet.blueberry.onboarding.DatabaseOpenErrorScreen
import io.bluewallet.blueberry.onboarding.InvalidSecretScreen
import io.bluewallet.blueberry.onboarding.OnboardingApp
import io.bluewallet.blueberry.onboarding.persistCreatedWallet
import io.bluewallet.blueberry.onboarding.persistImportedSecret
import io.bluewallet.blueberry.onboarding.persistSyncYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.ui.BwTheme
import io.bluewallet.blueberry.ui.ScreenPushOverlay
import io.bluewallet.blueberry.wallet.WalletSecretInspection
import io.bluewallet.blueberry.wallet.inspectWalletSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

internal val LocalAppearanceChange =
    staticCompositionLocalOf<(AppearancePreference) -> Unit> { {} }

internal val LocalAlwaysShowSync = staticCompositionLocalOf { false }

internal val LocalHomeVisible = staticCompositionLocalOf { true }

internal val LocalAlwaysShowSyncChange = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

private class OpenedDatabase(
    path: String,
) {
    val result: Result<Database> = runCatching { createSqliteDatabase(path) }

    @Volatile private var closed = false

    fun close() {
        if (closed) return
        closed = true
        result.getOrNull()?.close()
    }
}

@Composable
fun App(databasePath: String) {
    var session by remember { mutableStateOf(0) }
    val opened = remember(databasePath, session) { OpenedDatabase(databasePath) }
    DisposableEffect(opened) {
        onDispose { opened.close() }
    }
    val db = opened.result.getOrNull()
    val systemDark = isSystemInDarkTheme()
    var appearance by remember(databasePath, session) {
        mutableStateOf(db?.let { loadAppearance(it) } ?: AppearancePreference.System)
    }
    CompositionLocalProvider(
        LocalAppearanceChange provides { next ->
            appearance = next
            db?.let { saveAppearance(it, next) }
        },
    ) {
        BwTheme(dark = resolveDarkTheme(appearance, systemDark)) {
            var showSettings by remember { mutableStateOf(false) }
            var showReceive by remember { mutableStateOf(false) }
            var showSend by remember { mutableStateOf(false) }
            var showCoins by remember { mutableStateOf(false) }
            var showTxid by remember { mutableStateOf<String?>(null) }
            val openError = opened.result.exceptionOrNull()
            if (openError != null) {
                DatabaseOpenErrorScreen(openError.message ?: openError.toString())
                return@BwTheme
            }
            checkNotNull(db)
            var alwaysShowSync by remember(databasePath, session) {
                mutableStateOf(loadAlwaysShowSyncProgress(db))
            }
            var gate by remember(databasePath, session) {
                mutableStateOf(
                    resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db)),
                )
            }

            fun refreshGate() {
                gate = resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db))
            }
            val started = gate is OnboardingGate.Start
            val runtime =
                remember(databasePath, session, started) {
                    if (started) PeersRuntime(db) else null
                }
            val scope = rememberCoroutineScope()
            DisposableEffect(runtime) {
                val job = scope.launch(Dispatchers.Default) { runtime?.start() }
                onDispose {
                    job.cancel()
                    runtime?.stop()
                }
            }
            val homeVisible =
                !showSettings && !showReceive && !showSend && !showCoins && showTxid == null
            CompositionLocalProvider(
                LocalAlwaysShowSync provides alwaysShowSync,
                LocalAlwaysShowSyncChange provides { on ->
                    alwaysShowSync = on
                    saveAlwaysShowSyncProgress(db, on)
                },
                LocalHomeVisible provides homeVisible,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BackHandler(enabled = true) { /* home / idle: no-op, do not finish */ }
                    when (val current = gate) {
                        is OnboardingGate.Start ->
                            PeersScreen(
                                db = db,
                                store = checkNotNull(runtime).store,
                                headersStore = checkNotNull(runtime).headersStore,
                                filtersStore = checkNotNull(runtime).filtersStore,
                                matchingStore = checkNotNull(runtime).matchingStore,
                                blocksStore = checkNotNull(runtime).blocksStore,
                                walletTxsStore = checkNotNull(runtime).walletTxsStore,
                                onOpenSettings = { showSettings = true },
                                onOpenReceive = { showReceive = true },
                                onOpenSend = { showSend = true },
                                onOpenCoins = { showCoins = true },
                                onOpenTx = { showTxid = it },
                                onDetailedSyncChange = { value ->
                                    scope.launch(Dispatchers.Default) { saveHomeDetailedSync(db, value) }
                                },
                            )
                        is OnboardingGate.ExitInvalid -> InvalidSecretScreen(current.detail)
                        is OnboardingGate.Onboard ->
                            OnboardingApp(
                                startAtYearStep = current.startAtYearStep,
                                onFinished = { refreshGate() },
                                persistImportedSecret = { persistImportedSecret(db, it) },
                                persistCreatedWallet = { persistCreatedWallet(db, it) },
                                persistSyncYear = { persistSyncYear(db, it) },
                            )
                    }
                    ScreenPushOverlay(
                        visible = showReceive && runtime != null,
                        onDismiss = { showReceive = false },
                    ) {
                        val peers = runtime
                        if (peers != null) {
                            ReceiveScreen(runtime = peers, db = db, onBack = { showReceive = false })
                        }
                    }
                    ScreenPushOverlay(
                        visible = showSend && runtime != null,
                        onDismiss = { showSend = false },
                    ) {
                        val peers = runtime
                        if (peers != null) {
                            SendScreen(runtime = peers, db = db, onBack = { showSend = false })
                        }
                    }
                    ScreenPushOverlay(
                        visible = showCoins && runtime != null,
                        onDismiss = { showCoins = false },
                    ) {
                        val peers = runtime
                        if (peers != null) {
                            CoinsScreen(runtime = peers, db = db, onBack = { showCoins = false })
                        }
                    }
                    val openTxid = showTxid
                    var txOverlayId by remember { mutableStateOf<String?>(null) }
                    if (openTxid != null) txOverlayId = openTxid
                    ScreenPushOverlay(
                        visible = openTxid != null && runtime != null,
                        onDismiss = { showTxid = null },
                    ) {
                        val peers = runtime
                        val txid = txOverlayId
                        if (peers != null && txid != null) {
                            TxDetailsScreen(
                                runtime = peers,
                                db = db,
                                txid = txid,
                                onBack = { showTxid = null },
                            )
                        }
                    }
                    ScreenPushOverlay(visible = showSettings, onDismiss = { showSettings = false }) {
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
                            onClearStorage = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.Default) { runtime?.stop() }
                                        opened.close()
                                        withContext(Dispatchers.Default) {
                                            deleteSqliteDatabaseFiles(databasePath)
                                        }
                                    } finally {
                                        showSettings = false
                                        session += 1
                                    }
                                }
                            },
                            onBack = { showSettings = false },
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun AppPreview() = App(databasePath = ":memory:")
