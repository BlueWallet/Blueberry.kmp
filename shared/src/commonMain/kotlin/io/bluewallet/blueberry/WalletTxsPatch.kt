package io.bluewallet.blueberry

/** Update one UTXO row in the live snapshot without rewalking history. */
internal fun patchUtxoRow(
    walletTxsStore: WalletTxsStore,
    outpoint: String,
    transform: (WalletUtxoRow) -> WalletUtxoRow,
) {
    val cur = walletTxsStore.get()
    var changed = false
    val utxos =
        cur.utxos.map { row ->
            if (row.key != outpoint) {
                row
            } else {
                changed = true
                transform(row)
            }
        }
    if (changed) walletTxsStore.apply(cur.copy(utxos = utxos))
}
