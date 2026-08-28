package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.OnboardingGate
import io.bluewallet.blueberry.boot.deleteSqliteDatabaseFiles
import io.bluewallet.blueberry.boot.formatDatabaseGigabytes
import io.bluewallet.blueberry.boot.sqliteDatabaseBytes
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.resolveOnboardingGate
import io.bluewallet.blueberry.boot.saveHomeDetailedSync
import io.bluewallet.blueberry.onboarding.DatabaseOpenErrorScreen
import io.bluewallet.blueberry.onboarding.InvalidSecretScreen
import io.bluewallet.blueberry.onboarding.OnboardingApp
import io.bluewallet.blueberry.onboarding.persistCreatedWallet
import io.bluewallet.blueberry.onboarding.persistImportedSecret
import io.bluewallet.blueberry.onboarding.persistSyncYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwTheme
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WalletSecretInspection
import io.bluewallet.blueberry.wallet.inspectWalletSecret
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class OpenedDatabase(path: String) {
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
    BwTheme {
        var session by remember { mutableStateOf(0) }
        var showSettings by remember { mutableStateOf(false) }
        var showReceive by remember { mutableStateOf(false) }
        var showSend by remember { mutableStateOf(false) }
        val opened = remember(databasePath, session) { OpenedDatabase(databasePath) }
        DisposableEffect(opened) {
            onDispose { opened.close() }
        }
        val db = opened.result.getOrNull()
        val openError = opened.result.exceptionOrNull()
        if (openError != null) {
            DatabaseOpenErrorScreen(openError.message ?: openError.toString())
            return@BwTheme
        }
        checkNotNull(db)
        var gate by remember(databasePath, session) {
            mutableStateOf(
                resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db)),
            )
        }
        fun refreshGate() {
            gate = resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db))
        }
        val started = gate is OnboardingGate.Start
        val runtime = remember(databasePath, session, started) {
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
        if (showReceive && runtime != null) {
            ReceiveScreen(
                runtime = runtime,
                db = db,
                onBack = { showReceive = false },
            )
            return@BwTheme
        }
        if (showSend && runtime != null) {
            SendScreen(
                runtime = runtime,
                db = db,
                onBack = { showSend = false },
            )
            return@BwTheme
        }
        if (showSettings) {
            SettingsScreen(
                databaseSize = remember(databasePath, session) {
                    formatDatabaseGigabytes(sqliteDatabaseBytes(databasePath))
                },
                secret = remember(databasePath, session) {
                    (inspectWalletSecret(db) as? WalletSecretInspection.Ok)?.value
                },
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
            return@BwTheme
        }
        when (val current = gate) {
            is OnboardingGate.Start -> PeersScreen(
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
                onDetailedSyncChange = { value ->
                    scope.launch(Dispatchers.Default) { saveHomeDetailedSync(db, value) }
                },
            )
            is OnboardingGate.ExitInvalid -> InvalidSecretScreen(current.detail)
            is OnboardingGate.Onboard -> OnboardingApp(
                startAtYearStep = current.startAtYearStep,
                onFinished = { refreshGate() },
                persistImportedSecret = { persistImportedSecret(db, it) },
                persistCreatedWallet = { persistCreatedWallet(db, it) },
                persistSyncYear = { persistSyncYear(db, it) },
            )
        }
    }
}

@Preview
@Composable
fun AppPreview() = App(databasePath = ":memory:")