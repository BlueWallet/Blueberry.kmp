package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.WifReceiveTxRow
import io.bluewallet.blueberry.wallet.buildSend
import io.bluewallet.blueberry.wallet.firstUnusedInternalAddress
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.preferredWifReceiveAddress

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

/**
 * BIP84: change from first unused internal.
 * WIF: preferred receive address.
 * Address: the sole watched address.
 * MAX: change address is unused (builder sends all to dest).
 */
fun buildActiveSendTx(
    db: Database,
    wallet: Wallet,
    params: SendBuildParams,
): BuildSendResult {
    wallet.syncFromDb()
    val watch = wallet.snapshot()
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
                val change =
                    firstUnusedInternalAddress(watch, used.internal)
                        ?: throw IllegalArgumentException("no unused change address in watch window")
                change.address
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
