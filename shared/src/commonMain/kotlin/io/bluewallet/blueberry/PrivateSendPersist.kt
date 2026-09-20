package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.wallet.SignedSendResult

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
) {
    if (!shouldPersistPrivateSend(broadcast.phase, broadcast.txHex, row.txHex)) return
    db.privateSends.upsert(row)
}
