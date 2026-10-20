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
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.AppearancePreference
import io.bluewallet.blueberry.boot.OnboardingGate
import io.bluewallet.blueberry.boot.deleteSqliteDatabaseFiles
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.loadAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.loadAppearance
import io.bluewallet.blueberry.boot.resolveDarkTheme
import io.bluewallet.blueberry.boot.resolveOnboardingGate
import io.bluewallet.blueberry.boot.saveAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.saveAppearance
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.onboarding.DatabaseOpenErrorScreen
import io.bluewallet.blueberry.onboarding.InvalidSecretScreen
import io.bluewallet.blueberry.onboarding.OnboardingApp
import io.bluewallet.blueberry.onboarding.OnboardingPersist
import io.bluewallet.blueberry.onboarding.forgetImportedSecret
import io.bluewallet.blueberry.onboarding.persistCreatedWallet
import io.bluewallet.blueberry.onboarding.persistImportedSecret
import io.bluewallet.blueberry.onboarding.persistSyncYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.ui.BwTheme
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
                val job =
                    scope.launch(Dispatchers.Default) {
                        val peers = runtime
                        if (peers != null) {
                            startPeersThenRestoreLabels(
                                startPeers = { peers.start() },
                                restoreLabels = { restoreWalletLabelsOnColdStart(db) },
                                onRestored = {
                                    val at = nowMillis()
                                    peers.walletTxsStore.apply(snapshotFromDb(db, at, at, peers.wallet))
                                },
                            )
                        }
                    }
                onDispose {
                    job.cancel()
                    runtime?.stop()
                }
            }
            CompositionLocalProvider(
                LocalAlwaysShowSync provides alwaysShowSync,
                LocalAlwaysShowSyncChange provides { on ->
                    alwaysShowSync = on
                    saveAlwaysShowSyncProgress(db, on)
                },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val current = gate) {
                        is OnboardingGate.Start -> {
                            val peers = checkNotNull(runtime)
                            WalletNavHost(
                                db = db,
                                runtime = peers,
                                databasePath = databasePath,
                                session = session,
                                onClearStorage = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.Default) { peers.stop() }
                                            opened.close()
                                            withContext(Dispatchers.Default) {
                                                deleteSqliteDatabaseFiles(databasePath)
                                            }
                                        } finally {
                                            session += 1
                                        }
                                    }
                                },
                            )
                        }
                        is OnboardingGate.ExitInvalid -> InvalidSecretScreen(current.detail)
                        is OnboardingGate.Onboard ->
                            OnboardingApp(
                                startAtYearStep = current.startAtYearStep,
                                onFinished = { refreshGate() },
                                persist =
                                    OnboardingPersist(
                                        importedSecret = { persistImportedSecret(db, it) },
                                        createdWallet = { persistCreatedWallet(db, it) },
                                        syncYear = { persistSyncYear(db, it) },
                                        forgetSecret = { forgetImportedSecret(db) },
                                    ),
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
