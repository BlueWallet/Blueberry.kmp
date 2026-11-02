package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES
import io.bluewallet.blueberry.wallet.MAX_WATCH_COUNT
import io.bluewallet.blueberry.wallet.MnemonicChangePick
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.WatchWallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.WifReceiveTxRow
import io.bluewallet.blueberry.wallet.buildSend
import io.bluewallet.blueberry.wallet.loadHdWatchGaps
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.pickMnemonicChange
import io.bluewallet.blueberry.wallet.preferredWifReceiveAddress
import io.bluewallet.blueberry.wallet.rebuildSendPsbt
import io.bluewallet.blueberry.wallet.resolvedScriptType
import io.bluewallet.blueberry.wallet.saveHdWatchGaps
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

private fun requireAccepted(accept: () -> Boolean) {
    if (!accept()) throw CancellationException("build dropped")
}

data class SendBuildParams(
    val utxos: List<SendInputUtxo>,
    val toAddress: String,
    val amountSats: SendAmount,
    val feeRateSatPerVb: Double,
)

sealed class PickUtxos<out T> {
    data class Ok<T>(
        val selected: List<T>,
    ) : PickUtxos<T>()

    data class Error(
        val error: String,
    ) : PickUtxos<Nothing>()
}

fun <T> pickUtxosByKeys(
    utxos: List<T>,
    keys: List<String>,
    keyOf: (T) -> String,
): PickUtxos<T> {
    if (keys.isEmpty()) return PickUtxos.Error("no UTXOs selected")
    val selected = utxos.filter { keyOf(it) in keys.toSet() }
    if (selected.isEmpty()) return PickUtxos.Error("no UTXOs selected")
    if (selected.size != keys.size) {
        return PickUtxos.Error("some selected UTXOs are no longer available")
    }
    return PickUtxos.Ok(selected)
}

private fun attachNonWitnessUtxos(
    db: Database,
    utxos: List<SendInputUtxo>,
): List<SendInputUtxo> =
    utxos.map { utxo ->
        if (utxo.nonWitnessUtxo != null) return@map utxo
        val prev = db.transactions.get(utxo.txid) ?: return@map utxo
        utxo.copy(nonWitnessUtxo = prev.tx)
    }

/** The PSBT for a stored unsigned send. Each coin's script comes from its stored parent transaction. */
fun storedSendPsbt(
    db: Database,
    wallet: Wallet,
    unsignedTxHex: String,
    coins: List<PrivateSendCoin>,
): String {
    val utxos =
        coins.map { coin ->
            val parent = db.transactions.get(coin.txid)?.tx ?: throw IllegalArgumentException("missing parent transaction")
            val script = Transaction.read(parent).txOut[coin.vout].publicKeyScript
            SendInputUtxo(coin.txid, coin.vout, coin.valueSats, script.toByteArray(), nonWitnessUtxo = parent)
        }
    return rebuildSendPsbt(loadWalletSecret(db), wallet.snapshot(), unsignedTxHex, utxos)
}

internal fun grownInternalCount(
    current: Int,
    pickedIndex: Int,
): Int = minOf(maxOf(current, pickedIndex + 1), MAX_WATCH_COUNT)

/**
 * Grows one internal watch count inside a transaction, keeping whichever window is larger.
 * A dropped build writes nothing. A stale snapshot cannot shrink a window parse already grew.
 */
internal fun growWatchGap(
    db: Database,
    scriptType: AddressScriptType,
    index: Int,
    accept: () -> Boolean,
): Boolean {
    if (!accept()) return false
    var saved = false
    db.transaction {
        if (!accept()) return@transaction
        val fresh = loadHdWatchGaps(db)
        val current = fresh[scriptType]
        val bumped =
            fresh.with(
                scriptType,
                WatchGaps(current.external, grownInternalCount(current.internal, index)),
            )
        saveHdWatchGaps(db, fresh.mergeMax(bumped))
        saved = true
    }
    return saved && accept()
}

internal fun findMnemonicChangeAddress(
    watch: WatchWallet,
    pick: MnemonicChangePick,
): WatchAddress =
    watch.addresses.firstOrNull {
        it.change && it.resolvedScriptType() == pick.scriptType && it.index == pick.index
    } ?: watch.addresses
        .filter { it.change && it.resolvedScriptType() == pick.scriptType }
        .maxByOrNull { it.index }
        ?: error("wallet missing internal address for ${pick.scriptType}")

/**
 * Mnemonic: random script type, then first unused internal.
 * WIF: preferred receive address.
 * Address: the sole watched address.
 * MAX: change address is unused (builder sends all to dest).
 */
fun buildActiveSendTx(
    db: Database,
    wallet: Wallet,
    params: SendBuildParams,
    random: Random = Random.Default,
    accept: () -> Boolean = { true },
): BuildSendResult {
    requireAccepted(accept)
    wallet.syncFromDb()
    var watch = wallet.snapshot()
    val changeAddress =
        when {
            params.amountSats is SendAmount.Max -> params.toAddress
            watch.kind == WatchWalletKind.WIF ->
                preferredWifReceiveAddress(
                    watch,
                    db.transactions.list().map { WifReceiveTxRow(it.height, it.txIndex, it.tx) },
                ).address
            watch.kind == WatchWalletKind.ADDRESS -> {
                val addr =
                    watch.addresses.firstOrNull()
                        ?: throw IllegalArgumentException("address wallet missing watched address")
                addr.address
            }
            else -> {
                val used = usedWatchIndexes(db.transactions.list().map { it.tx }, watch)
                val usedInternal = HD_SCRIPT_TYPES.associateWith { used.get(it).internal }
                val pick = pickMnemonicChange(watch, usedInternal, random)
                val existing = pick.address
                if (existing != null) {
                    existing.address
                } else {
                    requireAccepted(accept)
                    val saved = growWatchGap(db, pick.scriptType, pick.index, accept)
                    requireAccepted { saved && accept() }
                    watch = wallet.refresh()
                    findMnemonicChangeAddress(watch, pick).address
                }
            }
        }
    return buildSend(
        io.bluewallet.blueberry.wallet.BuildSendTxParams(
            secret = loadWalletSecret(db),
            wallet = watch,
            utxos = attachNonWitnessUtxos(db, params.utxos),
            toAddress = params.toAddress,
            amountSats = params.amountSats,
            feeRateSatPerVb = params.feeRateSatPerVb,
            changeAddress = changeAddress,
        ),
    )
}
