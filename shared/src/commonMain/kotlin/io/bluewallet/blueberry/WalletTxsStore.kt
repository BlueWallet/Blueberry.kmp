package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.TxFee
import io.bluewallet.blueberry.parse.TxRow
import io.bluewallet.blueberry.parse.firstUtxoLabelByTxid
import io.bluewallet.blueberry.parse.formatBlockTimeLabel
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.parse.formatNetDelta
import io.bluewallet.blueberry.parse.inferPendingSendNetDelta
import io.bluewallet.blueberry.parse.padBlockTimeLabel
import io.bluewallet.blueberry.parse.scanWatchTxs
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.parse.shortOutpoint
import io.bluewallet.blueberry.parse.shortTxid
import io.bluewallet.blueberry.parse.utxoValueBar
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.headers.decodeBlockHeader
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.round

data class WalletTxRow(
    val txid: String,
    val shortTxid: String,
    val height: Int,
    val timeLabel: String,
    val netDeltaSats: Long,
    val netDeltaLabel: String,
    val paymentLabel: String? = null,
    val utxoLabel: String? = null,
    val privateSend: Boolean = false,
    val fee: TxFee? = null,
    val blockTimeS: Long? = null,
)

data class WalletUtxoRow(
    val key: String,
    val txid: String,
    val vout: Int,
    val outpointShort: String,
    val valueSats: Long,
    val scriptPubKey: ByteArray,
    val address: String? = null,
    val path: String? = null,
    val amountLabel: String,
    val height: Int,
    val ageLabel: String,
    val valueBar: String,
    val name: String?,
    val isChange: Boolean,
)

data class WalletTxsSnapshot(
    val at: Long? = null,
    val balanceSats: Long = 0,
    val balanceBtcLabel: String = formatBtc(0),
    val blocksParsed: Int = 0,
    val blocksTotal: Int = 0,
    val etaMs: Long? = null,
    val txs: List<WalletTxRow> = emptyList(),
    val utxos: List<WalletUtxoRow> = emptyList(),
    val utxosReady: Boolean = false,
)

interface WalletTxsStore {
    fun get(): WalletTxsSnapshot

    fun apply(snapshot: WalletTxsSnapshot)

    fun setBlockCounts(
        parsed: Int,
        total: Int,
        at: Long? = null,
    )

    fun setParsingActive(active: Boolean)

    fun subscribe(listener: () -> Unit): () -> Unit
}

val emptyWalletTxsSnapshot = WalletTxsSnapshot()

private const val MAX_SAMPLES = 8

private data class ParseSample(
    val at: Long,
    val downloaded: Int,
)

private data class WalletTxsState(
    val snapshot: WalletTxsSnapshot = emptyWalletTxsSnapshot,
    val parsingActive: Boolean = false,
    val samples: List<ParseSample> = emptyList(),
)

private fun parseOutpointKey(key: String): Pair<String, Int> {
    val i = key.lastIndexOf(':')
    return key.substring(0, i) to key.substring(i + 1).toInt()
}

private data class HeightTimeLabel(
    val label: String,
    val unixSeconds: Long?,
)

private fun timeLabelForHeight(
    db: Database,
    height: Int,
    nowMs: Long,
    cache: MutableMap<Int, HeightTimeLabel>,
): HeightTimeLabel {
    val hit = cache[height]
    if (hit != null) return hit
    val stored = db.headers.get(height)
    val resolved =
        if (stored == null) {
            HeightTimeLabel(padBlockTimeLabel("#$height"), null)
        } else {
            try {
                val timestamp = decodeBlockHeader(stored.header).timestamp
                HeightTimeLabel(formatBlockTimeLabel(timestamp, nowMs), timestamp)
            } catch (_: Throwable) {
                HeightTimeLabel(padBlockTimeLabel("#$height"), null)
            }
        }
    cache[height] = resolved
    return resolved
}

private fun addAdvancingSample(
    samples: List<ParseSample>,
    sample: ParseSample,
): List<ParseSample> {
    val last = samples.lastOrNull()
    if (last != null && sample.downloaded <= last.downloaded) return samples
    val next = samples + sample
    return if (next.size > MAX_SAMPLES) next.takeLast(MAX_SAMPLES) else next
}

private fun nextProgressSamples(
    samples: List<ParseSample>,
    prevDownloaded: Int,
    prevTotal: Int,
    at: Long,
    downloaded: Int,
    total: Int,
): List<ParseSample> {
    val wasDone = prevTotal > 0 && prevDownloaded >= prevTotal
    val isDone = total > 0 && downloaded >= total
    if (downloaded < prevDownloaded || (wasDone && !isDone)) {
        return listOf(ParseSample(at, downloaded))
    }
    return addAdvancingSample(samples, ParseSample(at, downloaded))
}

private fun estimateEtaMs(
    samples: List<ParseSample>,
    total: Int,
): Long? {
    if (samples.size < 2) return null
    val first = samples.first()
    val last = samples.last()
    val timeDelta = last.at - first.at
    if (timeDelta <= 0) return null
    val rate = (last.downloaded - first.downloaded).toDouble() / timeDelta
    if (total <= last.downloaded) return 0
    if (rate <= 0) return null
    val remaining = (total - last.downloaded).toDouble()
    return round(remaining / rate).toLong()
}

private data class PendingSendCandidate(
    val txid: String,
    val destination: String,
    val txHex: String,
    val coins: List<PrivateSendCoin>,
    val keepAddresses: List<String>,
    val createdAt: Long,
    val confirmedInBlock: Long,
    val markConfirmed: (Long) -> Unit,
    val privateSend: Boolean,
)

private fun pendingSendRows(
    db: Database,
    confirmedByTxid: Map<String, Long>,
    labelByTxid: Map<String, String>,
    utxoLabelByTxid: Map<String, String>,
    keepAddresses: List<String>,
): List<WalletTxRow> {
    val candidates =
        db.sends.list().map { row ->
            PendingSendCandidate(
                txid = row.txid,
                destination = row.destination,
                txHex = row.txHex,
                coins = row.coins,
                keepAddresses = keepAddresses,
                createdAt = row.createdAt,
                confirmedInBlock = row.confirmedInBlock,
                markConfirmed = { height -> db.sends.setConfirmedInBlock(row.txid, height) },
                privateSend = false,
            )
        } +
            db.privateSends.list().map { row ->
                PendingSendCandidate(
                    txid = row.txid,
                    destination = row.destination,
                    txHex = row.txHex,
                    coins = row.coins,
                    keepAddresses = keepAddresses + row.refundAddress,
                    createdAt = row.createdAt,
                    confirmedInBlock = row.confirmedInBlock,
                    markConfirmed = { height -> db.privateSends.setConfirmedInBlock(row.txid, height) },
                    privateSend = true,
                )
            }
    val stillPending = mutableListOf<PendingSendCandidate>()
    for (row in candidates) {
        val confirmedHeight = confirmedByTxid[row.txid]
        if (confirmedHeight != null) {
            if (row.confirmedInBlock == 0L) row.markConfirmed(confirmedHeight)
        } else if (row.confirmedInBlock == 0L) {
            stillPending += row
        }
    }
    return pendingWalletRows(stillPending, labelByTxid, utxoLabelByTxid)
}

private fun pendingWalletRows(
    pending: List<PendingSendCandidate>,
    labelByTxid: Map<String, String>,
    utxoLabelByTxid: Map<String, String>,
): List<WalletTxRow> =
    pending.sortedByDescending { it.createdAt }.map { row ->
        val delta =
            runCatching {
                inferPendingSendNetDelta(
                    coins = row.coins,
                    destination = row.destination,
                    txHex = row.txHex,
                    keepAddresses = row.keepAddresses,
                )
            }.getOrElse { -row.coins.sumOf { coin -> coin.valueSats } }
        pendingWalletTxRow(row, delta, labelByTxid, utxoLabelByTxid)
    }

private fun pendingWalletTxRow(
    row: PendingSendCandidate,
    delta: Long,
    labelByTxid: Map<String, String>,
    utxoLabelByTxid: Map<String, String>,
): WalletTxRow =
    WalletTxRow(
        txid = row.txid,
        shortTxid = shortTxid(row.txid),
        height = 0,
        timeLabel = padBlockTimeLabel("pending"),
        netDeltaSats = delta,
        netDeltaLabel = formatNetDelta(delta),
        paymentLabel = labelByTxid[row.txid],
        utxoLabel = utxoLabelByTxid[row.txid],
        privateSend = row.privateSend,
    )

fun snapshotFromDb(
    db: Database,
    at: Long,
    nowMs: Long = nowMillis(),
    wallet: Wallet? = null,
): WalletTxsSnapshot {
    val stored = db.transactions.list()
    val balanceSats = stored.fold(0L) { s, t -> s + t.netDeltaSats }
    val timeLabels = mutableMapOf<Int, HeightTimeLabel>()
    val labelByTxid = db.txPaymentLabels.list().associate { it.txid to it.label }
    val privateSendTxids =
        db.privateSends
            .list()
            .map { it.txid }
            .toSet()
    val nameByOutpoint = db.utxoNames.list().associate { it.outpoint to it.name }
    val utxoLabelByTxid = firstUtxoLabelByTxid(nameByOutpoint)

    var utxos = emptyList<WalletUtxoRow>()
    var fees = emptyMap<String, TxFee>()
    if (wallet != null) {
        wallet.syncFromDb()
        val scan =
            scanWatchTxs(
                stored.map { TxRow(it.txid, it.height, it.txIndex, it.tx) },
                wallet.scripts(),
            )
        fees = scan.fees
        val map = scan.utxos
        var maxValue = 0L
        for (u in map.values) {
            if (u.value > maxValue) maxValue = u.value
        }
        val watched = wallet.snapshot().addresses
        val watchByScript = watched.associateBy { scriptHex(it.scriptPubKey) }
        val changeScripts = watched.filter { it.change }.map { it.scriptPubKey }
        utxos =
            map.entries
                .map { (key, u) ->
                    val (txid, vout) = parseOutpointKey(key)
                    val height = u.height ?: 0
                    val watch = watchByScript[scriptHex(u.scriptPubKey)]
                    WalletUtxoRow(
                        key = key,
                        txid = txid,
                        vout = vout,
                        outpointShort = shortOutpoint(txid, vout),
                        valueSats = u.value,
                        scriptPubKey = u.scriptPubKey,
                        address = watch?.address,
                        path = watch?.path,
                        amountLabel = formatBtc(u.value),
                        height = height,
                        ageLabel = timeLabelForHeight(db, height, nowMs, timeLabels).label,
                        valueBar = utxoValueBar(u.value, maxValue),
                        name = nameByOutpoint[key],
                        isChange = changeScripts.any { it.contentEquals(u.scriptPubKey) },
                    )
                }.sortedWith(
                    compareByDescending<WalletUtxoRow> { it.height }
                        .thenBy { it.txid }
                        .thenBy { it.vout },
                )
    }

    val confirmedTxs =
        stored.map { tx ->
            val whenLabel = timeLabelForHeight(db, tx.height, nowMs, timeLabels)
            WalletTxRow(
                txid = tx.txid,
                shortTxid = shortTxid(tx.txid),
                height = tx.height,
                timeLabel = whenLabel.label,
                blockTimeS = whenLabel.unixSeconds,
                netDeltaSats = tx.netDeltaSats,
                netDeltaLabel = formatNetDelta(tx.netDeltaSats),
                paymentLabel = labelByTxid[tx.txid],
                utxoLabel = utxoLabelByTxid[tx.txid],
                privateSend = tx.txid in privateSendTxids,
                fee = fees[tx.txid],
            )
        }
    val pending =
        pendingSendRows(
            db = db,
            confirmedByTxid = stored.associate { it.txid to it.height.toLong() },
            labelByTxid = labelByTxid,
            utxoLabelByTxid = utxoLabelByTxid,
            keepAddresses =
                wallet
                    ?.snapshot()
                    ?.addresses
                    ?.map { it.address }
                    .orEmpty(),
        )
    return WalletTxsSnapshot(
        at = at,
        balanceSats = balanceSats,
        balanceBtcLabel = formatBtc(balanceSats),
        blocksParsed = db.parsedBlocks.count(),
        blocksTotal = db.blocks.count(),
        etaMs = null,
        txs = pending + confirmedTxs,
        utxos = utxos,
        utxosReady = wallet != null,
    )
}

@OptIn(ExperimentalAtomicApi::class)
private class WalletTxsStoreImpl : WalletTxsStore {
    private val state = AtomicReference(WalletTxsState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    override fun get(): WalletTxsSnapshot = state.load().snapshot

    override fun setParsingActive(active: Boolean) {
        while (true) {
            val cur = state.load()
            if (cur.parsingActive == active) return
            val nextSnap =
                if (!active && cur.snapshot.etaMs != null) {
                    cur.snapshot.copy(etaMs = null)
                } else {
                    cur.snapshot
                }
            val next = WalletTxsState(snapshot = nextSnap, parsingActive = active, samples = emptyList())
            if (state.compareAndSet(cur, next)) {
                if (nextSnap !== cur.snapshot) emitChange()
                return
            }
        }
    }

    override fun setBlockCounts(
        parsed: Int,
        total: Int,
        at: Long?,
    ) {
        while (true) {
            val cur = state.load()
            val snap = cur.snapshot
            if (snap.blocksParsed == parsed && snap.blocksTotal == total) return
            val wasDone = snap.blocksTotal > 0 && snap.blocksParsed >= snap.blocksTotal
            val isDone = total > 0 && parsed >= total
            var samples = cur.samples
            if (parsed < snap.blocksParsed || (wasDone && !isDone)) {
                samples = emptyList()
            }
            if (at != null && cur.parsingActive) {
                samples =
                    nextProgressSamples(
                        samples,
                        snap.blocksParsed,
                        snap.blocksTotal,
                        at,
                        parsed,
                        total,
                    )
            }
            val etaMs =
                when {
                    !cur.parsingActive -> null
                    isDone -> 0L
                    samples.size < 2 -> null
                    else -> estimateEtaMs(samples, total)
                }
            val next =
                cur.copy(
                    snapshot = snap.copy(blocksParsed = parsed, blocksTotal = total, etaMs = etaMs),
                    samples = samples,
                )
            if (state.compareAndSet(cur, next)) {
                emitChange()
                return
            }
        }
    }

    override fun apply(snapshot: WalletTxsSnapshot) {
        while (true) {
            val cur = state.load()
            if (!cur.parsingActive) {
                val updated =
                    WalletTxsState(
                        snapshot = snapshot.copy(etaMs = null),
                        parsingActive = false,
                        samples = emptyList(),
                    )
                if (state.compareAndSet(cur, updated)) {
                    emitChange()
                    return
                }
                continue
            }
            val prev = cur.snapshot
            if (snapshot.at == null) {
                val updated = cur.copy(snapshot = snapshot.copy(etaMs = null), samples = emptyList())
                if (state.compareAndSet(cur, updated)) {
                    emitChange()
                    return
                }
                continue
            }
            val nextSamples =
                nextProgressSamples(
                    cur.samples,
                    prev.blocksParsed,
                    prev.blocksTotal,
                    snapshot.at,
                    snapshot.blocksParsed,
                    snapshot.blocksTotal,
                )
            val hasBacklog = snapshot.blocksTotal > snapshot.blocksParsed
            val nextEta = if (hasBacklog) estimateEtaMs(nextSamples, snapshot.blocksTotal) else null
            val updated = cur.copy(snapshot = snapshot.copy(etaMs = nextEta), samples = nextSamples)
            if (state.compareAndSet(cur, updated)) {
                emitChange()
                return
            }
        }
    }

    override fun subscribe(listener: () -> Unit): () -> Unit {
        while (true) {
            val cur = listeners.load()
            if (listener in cur) break
            if (listeners.compareAndSet(cur, cur + listener)) break
        }
        return {
            while (true) {
                val cur = listeners.load()
                val next = cur - listener
                if (next === cur || listeners.compareAndSet(cur, next)) break
            }
        }
    }
}

fun createWalletTxsStore(): WalletTxsStore = WalletTxsStoreImpl()

fun hydrateWalletBlockCounts(
    db: Database,
    store: WalletTxsStore,
) {
    store.setBlockCounts(db.parsedBlocks.count(), db.blocks.count())
}

private fun openSendTxids(db: Database): Set<String> =
    db.sends
        .list()
        .mapNotNull { row -> if (row.confirmedInBlock == 0L) row.txid else null }
        .toSet() +
        db.privateSends.list().mapNotNull { row ->
            if (row.confirmedInBlock == 0L) row.txid else null
        }

private fun txSetUnchanged(
    db: Database,
    snap: WalletTxsSnapshot,
): Boolean {
    if (snap.at == null) return false
    val confirmed = snap.txs.filter { it.height != 0 }
    val pendingTxids =
        snap.txs
            .filter { it.height == 0 }
            .map { it.txid }
            .toSet()
    val fp = db.transactions.fingerprint()
    return fp.count == confirmed.size &&
        fp.netDeltaSum == snap.balanceSats &&
        fp.newestTxid == confirmed.firstOrNull()?.txid &&
        pendingTxids == openSendTxids(db)
}

fun hydrateWallet(
    db: Database,
    store: WalletTxsStore,
    wallet: Wallet?,
    at: Long,
) {
    val parsed = db.parsedBlocks.count()
    val total = db.blocks.count()
    val snap = store.get()
    val missingUtxos = wallet != null && !snap.utxosReady
    if (txSetUnchanged(db, snap) && !missingUtxos) {
        store.setBlockCounts(parsed, total, at)
        return
    }
    store.apply(snapshotFromDb(db, at, nowMillis(), wallet))
}

fun bindWalletTxsEvents(
    bus: MessageBus,
    db: Database,
    store: WalletTxsStore,
    wallet: Wallet? = null,
): () -> Unit {
    val a = bus.on(Event.WalletTxs) { hydrateWallet(db, store, wallet, it.at) }
    val b = bus.on(Event.BlocksProgress) { hydrateWalletBlockCounts(db, store) }
    val c = bus.on(Event.SyncIdle) { store.setParsingActive(true) }
    val d = bus.on(Event.SyncCatchup) { store.setParsingActive(false) }
    return {
        a()
        b()
        c()
        d()
    }
}
