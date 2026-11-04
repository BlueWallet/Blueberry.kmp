package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.Wallet

const val PRIVATE_SEND_PARTNER_ROCKETX = "ROCKETX"

fun shouldPersistPrivateSend(
    phase: String?,
    broadcastTxHex: String?,
    signedTxHex: String,
): Boolean = phase == "success" && broadcastTxHex == signedTxHex

fun privateSendRecord(
    session: PrivateSendSession,
    swap: RocketxSwap,
    signed: SignedSendResult,
): PrivateSendRow =
    PrivateSendRow(
        txid = signed.txid,
        partner = PRIVATE_SEND_PARTNER_ROCKETX,
        orderId = swap.requestId,
        txHex = signed.txHex,
        destination = session.destination,
        refundAddress = session.refundAddress,
        coins =
            session.utxos.map { utxo ->
                PrivateSendCoin(txid = utxo.txid, vout = utxo.vout, valueSats = utxo.valueSats)
            },
    )

fun persistPrivateSendIfBroadcast(
    db: Database,
    row: PrivateSendRow,
    broadcast: BroadcastSnapshot,
): Boolean {
    if (!shouldPersistPrivateSend(broadcast.phase, broadcast.txHex, row.txHex)) return false
    db.privateSends.upsert(row)
    db.broadcastVia.upsert(row.txid, BROADCAST_VIA_TOR)
    return true
}

fun persistPrivateSendAndRefresh(
    db: Database,
    store: WalletTxsStore,
    wallet: Wallet?,
    row: PrivateSendRow,
    broadcast: BroadcastSnapshot,
): Boolean {
    if (!persistPrivateSendIfBroadcast(db, row, broadcast)) return false
    hydrateWallet(db, store, wallet, nowMillis())
    return true
}
