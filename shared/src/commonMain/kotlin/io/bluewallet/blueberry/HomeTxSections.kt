package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class HomeTxKey(
    val snap: WalletTxsSnapshot,
    val wallet: Wallet?,
    val tipHeight: Int?,
) {
    fun sameAs(other: HomeTxKey): Boolean = snap === other.snap && wallet === other.wallet && tipHeight == other.tipHeight
}

private class HomeTxBuilt(
    val key: HomeTxKey,
    val sections: List<TxListSection>,
)

/** Home leaves composition under a pushed screen. Keeping the last build lets Back land on a full list. */
private object HomeTxCache {
    var last: HomeTxBuilt? = null
}

/**
 * The home day sections, built off the main thread: every row decodes its transaction. Until a new build lands,
 * the last list for the same wallet stays on screen.
 */
@Composable
internal fun rememberHomeTxSections(
    db: Database,
    wallet: Wallet?,
    snap: WalletTxsSnapshot,
    tipHeight: Int?,
): List<TxListSection> {
    val key = HomeTxKey(snap, wallet, tipHeight)
    val cached = HomeTxCache.last?.takeIf { it.key.wallet === wallet }
    val sections by produceState(cached?.sections.orEmpty(), snap, wallet, tipHeight) {
        val hit = HomeTxCache.last?.takeIf { it.key.sameAs(key) }
        value = hit?.sections ?: withContext(Dispatchers.Default) {
            txHomeSections(
                TxQuery(
                    db = db,
                    wallet = wallet,
                    snap = snap,
                    tipHeight = db.headers.tip()?.height,
                    nowEpochS = nowMillis() / 1000,
                ),
            )
        }.also { HomeTxCache.last = HomeTxBuilt(key, it) }
    }
    return sections
}
