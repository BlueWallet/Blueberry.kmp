package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.Wallet

fun shouldPersistSend(
    phase: String?,
    broadcastTxHex: String?,
    signedTxHex: String,
): Boolean = phase == "success" && broadcastTxHex == signedTxHex

fun sendRecord(
    destination: String,
    utxos: List<SendInputUtxo>,
    signed: SignedSendResult,
): SendRow =
    SendRow(
        txid = signed.txid,
        txHex = signed.txHex,
        destination = destination,
        coins =
            utxos.map { utxo ->
                PrivateSendCoin(txid = utxo.txid, vout = utxo.vout, valueSats = utxo.valueSats)
            },
    )

fun persistSendIfBroadcast(
    db: Database,
    row: SendRow,
    broadcast: BroadcastSnapshot,
): Boolean {
    if (!shouldPersistSend(broadcast.phase, broadcast.txHex, row.txHex)) return false
    db.sends.upsert(row)
    return true
}

fun <T : Any> armedAfterPersist(
    current: T?,
    persisted: T?,
    wrote: Boolean,
): T? {
    if (!wrote) return current
    return if (current === persisted) null else current
}

fun persistSendAndRefresh(
    db: Database,
    store: WalletTxsStore,
    wallet: Wallet?,
    row: SendRow,
    broadcast: BroadcastSnapshot,
): Boolean {
    if (!persistSendIfBroadcast(db, row, broadcast)) return false
    hydrateWallet(db, store, wallet, nowMillis())
    return true
}
