package io.bluewallet.blueberry

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.parse.outpointKey
import io.bluewallet.blueberry.parse.prevoutKey
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.storage.PrivateSendCoin

internal fun displayOutputs(
    row: WalletTxRow,
    tx: Transaction,
    facts: TxFacts,
    destination: String?,
): List<TxDisplayOutput> {
    var gaveDestination = false
    return tx.txOut.mapIndexed { vout, output ->
        val key = outpointKey(row.txid, vout)
        val script = scriptHex(output.publicKeyScript.toByteArray())
        val watch = facts.watchByScript[script]
        val held = facts.utxos[key]
        val graphCoin = facts.graph.coins[key]
        val mine = watch != null || held != null || graphCoin != null
        val address =
            when {
                mine -> watch?.address ?: held?.address
                !gaveDestination && destination != null -> destination.also { gaveDestination = true }
                else -> null
            }
        val spender = facts.spends[key]
        val group = facts.groups[key]
        TxDisplayOutput(
            vout = vout,
            valueSat = output.amount.toLong(),
            mine = mine,
            inWallet = held != null,
            change = watch?.change == true || held?.isChange == true || graphCoin?.isChange == true,
            address = address,
            addressIndex = watch?.index ?: pathIndex(held?.path),
            addressUseCount = addressUses(facts, script, key),
            label = facts.names[key].orEmpty(),
            frozen = key in facts.frozen,
            spent = spender != null,
            spentTxid = spender?.txid,
            spentLabel = spender?.let { facts.labels[it.txid] },
            groupName = group?.first,
            groupSize = group?.second ?: 1,
        )
    }
}

internal fun displayInputs(
    row: WalletTxRow,
    tx: Transaction,
    facts: TxFacts,
    pack: SendPack?,
): List<TxDisplayInputCoin> {
    val pending = row.ownSend && row.height == 0 && !row.replaced
    val fromSend = if (pending) pack?.coins.orEmpty() else emptyList()
    return if (fromSend.isNotEmpty()) {
        fromSend.map { coin -> inputFromCoin(coin, facts) }
    } else {
        tx.txIn.mapNotNull { input -> inputFromPrevout(prevoutKey(input), facts, pack) }
    }
}

private fun inputFromCoin(
    coin: PrivateSendCoin,
    facts: TxFacts,
): TxDisplayInputCoin {
    val key = outpointKey(coin.txid, coin.vout)
    val held = facts.utxos[key]
    return inputCoin(key, held?.valueSats ?: coin.valueSats, facts, held?.label)
}

private fun inputFromPrevout(
    key: String,
    facts: TxFacts,
    pack: SendPack?,
): TxDisplayInputCoin? {
    val held = facts.utxos[key]
    val graphCoin = facts.graph.coins[key]
    val sendCoin = pack?.coins?.find { outpointKey(it.txid, it.vout) == key }
    val value = held?.valueSats ?: graphCoin?.valueSat ?: sendCoin?.valueSats
    val ours = held != null || graphCoin != null || sendCoin != null
    return if (!ours || value == null) null else inputCoin(key, value, facts, held?.label)
}

private fun inputCoin(
    key: String,
    valueSat: Long,
    facts: TxFacts,
    label: String?,
): TxDisplayInputCoin {
    val group = facts.groups[key]
    return TxDisplayInputCoin(
        outpoint = key,
        valueSat = valueSat,
        label = label ?: facts.names[key],
        groupName = group?.first,
        groupSize = group?.second ?: 1,
        colorArgb = utxoCircleArgb(key),
    )
}

private fun addressUses(
    facts: TxFacts,
    script: String,
    key: String,
): Int {
    val seen =
        facts.graph.coins.values
            .count { it.scriptHex == script }
    val here = facts.graph.coins[key]?.scriptHex == script
    val count = if (here) seen else seen + 1
    return count.coerceAtLeast(1)
}

private fun pathIndex(path: String?): Int? = path?.substringAfterLast('/')?.substringBefore('\'')?.toIntOrNull()
