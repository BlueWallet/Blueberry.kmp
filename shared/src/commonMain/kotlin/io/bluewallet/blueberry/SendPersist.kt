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

fun unconfirmedSendOpen(
    db: Database,
    txid: String,
): Boolean {
    val sendOpen = db.sends.get(txid)?.confirmedInBlock == 0L
    val privateOpen = db.privateSends.get(txid)?.confirmedInBlock == 0L
    return sendOpen || privateOpen
}

/** Drops a payment that never confirmed, so its coins can be spent again. A confirmed row stays. */
fun abandonUnconfirmedSend(
    db: Database,
    runtime: PeersRuntime,
    txid: String,
): Boolean {
    if (!unconfirmedSendOpen(db, txid)) return false
    if (db.sends.get(txid)?.confirmedInBlock == 0L) db.sends.delete(txid)
    if (db.privateSends.get(txid)?.confirmedInBlock == 0L) db.privateSends.delete(txid)
    runtime.broadcastStore.sessions().forEach { session ->
        val id = session.snapshot.id
        if (session.receipt?.txid == txid && id != null) {
            runtime.dropArmedBroadcast(id)
            runtime.broadcastStore.dismiss(id)
        }
    }
    hydrateWallet(db, runtime.walletTxsStore, runtime.wallet, nowMillis())
    return true
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
