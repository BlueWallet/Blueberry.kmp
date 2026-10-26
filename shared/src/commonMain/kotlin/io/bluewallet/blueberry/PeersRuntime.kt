package io.bluewallet.blueberry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.bluewallet.blueberry.blocks.modules.BlocksDownloadOptions
import io.bluewallet.blueberry.blocks.modules.createBlocksDownloadModule
import io.bluewallet.blueberry.boot.loadSyncFromYear
import io.bluewallet.blueberry.broadcast.createBroadcastModule
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.filters.modules.FiltersDownloadOptions
import io.bluewallet.blueberry.filters.modules.FiltersMatchingOptions
import io.bluewallet.blueberry.filters.modules.createFiltersDownloadModule
import io.bluewallet.blueberry.filters.modules.createFiltersMatchingModule
import io.bluewallet.blueberry.headers.consensusForYear
import io.bluewallet.blueberry.headers.modules.ChainHeadersOptions
import io.bluewallet.blueberry.headers.modules.createChainHeadersModule
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.modules.ParseBlocksOptions
import io.bluewallet.blueberry.parse.modules.createParseBlocksModule
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.PeersDiscoveryOptions
import io.bluewallet.blueberry.peers.modules.createPeersDiscoveryModule
import io.bluewallet.blueberry.peers.net.createPlatformNet
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.sync.modules.createSyncIdleModule
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.outpointKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

fun bindPeerSocketEvents(
    bus: MessageBus,
    db: Database,
    store: PeerSocketsStore,
): () -> Unit {
    val a = bus.on(Event.PeersUpdated) { hydratePeers(db, store) }
    val b = bus.on(Event.PeersSockets) { store.applyEvent(it.kind, it.open) }
    return {
        a()
        b()
    }
}

class PeersRuntime(
    private val db: Database,
) {
    val bus: MessageBus = createMessageBus()
    val store: PeerSocketsStore = createPeerSocketsStore()
    val headersStore: HeadersProgressStore = createHeadersProgressStore()
    val filtersStore: FiltersProgressStore = createFiltersProgressStore()
    val matchingStore: MatchingProgressStore = createMatchingProgressStore()
    val blocksStore: BlocksMatchedStore = createBlocksMatchedStore()
    val walletTxsStore: WalletTxsStore = createWalletTxsStore()
    val broadcastStore: BroadcastStore = createBroadcastStore()

    /** Bumps whenever [wallet] is set or cleared, so Compose can retry work that saw a null wallet. */
    var walletRevision by mutableStateOf(0)
        private set

    @Volatile private var walletRef: Wallet? = null

    var wallet: Wallet?
        get() = walletRef
        private set(value) {
            walletRef = value
            walletRevision++
        }

    /** Test seam. Production assigns [wallet] from [start]. */
    internal fun installWallet(next: Wallet?) {
        wallet = next
    }

    @OptIn(ExperimentalAtomicApi::class)
    private val pendingSends = AtomicReference<Map<String, SendRow>>(emptyMap())

    @OptIn(ExperimentalAtomicApi::class)
    private val pendingPrivateSends = AtomicReference<Map<String, PrivateSendRow>>(emptyMap())

    @OptIn(ExperimentalAtomicApi::class)
    fun armSendPersist(
        id: String,
        row: SendRow,
    ) {
        putPending(pendingSends, id, row)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun armPrivateSendPersist(
        id: String,
        row: PrivateSendRow,
    ) {
        putPending(pendingPrivateSends, id, row)
    }

    /** Cleared whenever the wallet store rebuilds from the database. */
    private val storedHolds = StoredHoldsCache { loadStoredHolds(db) }

    /**
     * Outpoints a live broadcast or an unconfirmed stored send (a PSBT included) still holds.
     * A finished error keeps the row for Retry and no longer holds the coins, since no node has it.
     * [exceptTxid] leaves out one transaction's own hold, so a screen can tell its own coins from coins another payment took.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun reservedOutpoints(
        exceptTxid: String? = null,
        stored: List<Pair<String, String>> = storedHolds.get(),
    ): Set<String> {
        val live = liveBroadcastIds(broadcastStore)
        val armed =
            pendingSends
                .load()
                .filter { it.key in live && it.value.txid != exceptTxid }
                .values
                .flatMap { it.coins } +
                pendingPrivateSends
                    .load()
                    .filter { it.key in live && it.value.txid != exceptTxid }
                    .values
                    .flatMap { it.coins }
        val held = stored.mapNotNull { (txid, key) -> if (txid == exceptTxid) null else key }
        return armed.map { outpointKey(it.txid, it.vout) }.toSet() + held
    }

    /** Stored holds if already read, without touching the database. */
    internal val cachedStoredHolds: List<Pair<String, String>>? get() = storedHolds.peek()

    /** Drops the in-memory record for a broadcast the user chose to forget. */
    @OptIn(ExperimentalAtomicApi::class)
    fun dropArmedBroadcast(id: String) {
        removePending(pendingSends, id)
        removePending(pendingPrivateSends, id)
    }

    /**
     * Writes the send once a node was given it, so the coin hold survives the app dying mid-broadcast.
     * Success writes it again and drops the armed record. Only an error before any node was given the
     * transaction drops the row; once handed off, a node may still relay it, so the send stays stored.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun persistArmedBroadcast(
        id: String,
        handedOff: Boolean = false,
    ) {
        val snap = broadcastStore.get(id) ?: return
        val send = pendingSends.load()[id]
        val priv = pendingPrivateSends.load()[id]
        storedHolds.clear()
        if (snap.phase == "error" && !snap.handedOff) {
            (send?.txid ?: priv?.txid)?.let { dropUnsentSend(db, walletTxsStore, wallet, it) }
        } else {
            val sent = if (handedOff || snap.phase == "error") snap.copy(phase = "success") else snap
            val wroteSend = send != null && persistSendAndRefresh(db, walletTxsStore, wallet, send, sent)
            val wrotePriv = priv != null && persistPrivateSendAndRefresh(db, walletTxsStore, wallet, priv, sent)
            if (wroteSend && !handedOff) removePending(pendingSends, id)
            if (wrotePriv && !handedOff) removePending(pendingPrivateSends, id)
        }
        storedHolds.clear()
    }

    private val net = createPlatformNet()
    private val discovery: Module =
        createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(net = net),
        )
    private var headers: Module? = null
    private var filters: Module? = null
    private var matching: Module? = null
    private var blocks: Module? = null
    private var parseBlocks: Module? = null
    private var syncIdle: Module? = null
    private var broadcast: Module? = null
    private var unbind: (() -> Unit)? = null

    @Volatile private var alive = true
    private var started = false
    private val lifecycleMutex = Mutex()

    init {
        walletTxsStore.subscribe { storedHolds.clear() }
        hydratePeers(db, store)
        hydrateHeaders(db, headersStore)
        hydrateFilters(db, filtersStore)
        hydrateMatching(db, matchingStore)
        hydrateBlocks(db, blocksStore)
        hydrateWallet(db, walletTxsStore, null, nowMillis())
    }

    suspend fun start() {
        lifecycleMutex.withLock {
            if (!alive || started) return@withLock
            started = true
            try {
                startLocked()
            } catch (e: CancellationException) {
                stopLocked()
                throw e
            }
        }
    }

    private suspend fun startLocked() {
        if (!alive) return
        val unbindPeers = bindPeerSocketEvents(bus, db, store)
        val unbindHeaders = bindHeaderProgressEvents(bus, db, headersStore)
        val unbindFilters = bindFilterProgressEvents(bus, db, filtersStore)
        val unbindMatching = bindMatchingProgressEvents(bus, db, matchingStore)
        val unbindBlocks = bindBlocksProgressEvents(bus, db, blocksStore)
        val sharedWallet =
            try {
                withContext(Dispatchers.Default) { createWallet(db) }.also { wallet = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "parse-blocks",
                        status = ModuleStatus.ERROR,
                        detail = e.message ?: e.toString(),
                    ),
                )
                bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "filters-matching",
                        status = ModuleStatus.ERROR,
                        detail = e.message ?: e.toString(),
                    ),
                )
                null
            }
        val unbindWallet = bindWalletTxsEvents(bus, db, walletTxsStore, sharedWallet)
        val unbindBroadcast =
            bindBroadcastEvents(
                bus,
                broadcastStore,
                onHandedOff = { id -> persistArmedBroadcast(id, handedOff = true) },
                onDone = { id -> persistArmedBroadcast(id) },
            )
        unbind = {
            unbindPeers()
            unbindHeaders()
            unbindFilters()
            unbindMatching()
            unbindBlocks()
            unbindWallet()
            unbindBroadcast()
        }
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        hydratePeers(db, store)
        hydrateHeaders(db, headersStore)
        hydrateFilters(db, filtersStore)
        hydrateMatching(db, matchingStore)
        hydrateBlocks(db, blocksStore)
        hydrateWallet(db, walletTxsStore, sharedWallet, nowMillis())
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        try {
            val blocksModule =
                createBlocksDownloadModule(
                    ModuleContext(bus, db),
                    BlocksDownloadOptions(net = net),
                )
            blocks = blocksModule
            blocksModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "blocks-download",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            val idleModule = createSyncIdleModule(ModuleContext(bus, db))
            syncIdle = idleModule
            idleModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "sync-idle",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            discovery.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "peers-discovery",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            val headersModule =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = net,
                        consensus = consensusForYear(loadSyncFromYear(db)),
                    ),
                )
            headers = headersModule
            headersModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "chain-headers",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            val filtersModule =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(net = net),
                )
            filters = filtersModule
            filtersModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "filters-download",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        if (sharedWallet != null) {
            try {
                val parseModule =
                    createParseBlocksModule(
                        ModuleContext(bus, db),
                        ParseBlocksOptions(wallet = sharedWallet),
                    )
                parseBlocks = parseModule
                parseModule.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "parse-blocks",
                        status = ModuleStatus.ERROR,
                        detail = e.message ?: e.toString(),
                    ),
                )
            }
            try {
                val matchingModule =
                    createFiltersMatchingModule(
                        ModuleContext(bus, db),
                        FiltersMatchingOptions(wallet = sharedWallet),
                    )
                matching = matchingModule
                matchingModule.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "filters-matching",
                        status = ModuleStatus.ERROR,
                        detail = e.message ?: e.toString(),
                    ),
                )
            }
        }
        try {
            val broadcastModule = createBroadcastModule(ModuleContext(bus, db))
            broadcast = broadcastModule
            broadcastModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "broadcast",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        if (!alive) {
            broadcast?.stop()
            matching?.stop()
            parseBlocks?.stop()
            syncIdle?.stop()
            blocks?.stop()
            filters?.stop()
            headers?.stop()
            discovery.stop()
        }
    }

    fun stop() {
        runBlocking {
            lifecycleMutex.withLock { stopLocked() }
        }
    }

    private fun stopLocked() {
        alive = false
        started = false
        broadcast?.stop()
        broadcast = null
        matching?.stop()
        matching = null
        parseBlocks?.stop()
        parseBlocks = null
        syncIdle?.stop()
        syncIdle = null
        blocks?.stop()
        blocks = null
        filters?.stop()
        filters = null
        headers?.stop()
        headers = null
        discovery.stop()
        unbind?.invoke()
        unbind = null
        wallet = null
    }
}

/** Reserved outpoints; [complete] is false until stored sends have been read. */
data class HeldOutpoints(
    val keys: Set<String>,
    val complete: Boolean,
)

/**
 * Live broadcasts from memory plus stored holds only if already cached, so it never touches the database
 * and is safe on the UI thread.
 */
fun PeersRuntime.reservedOutpointsNow(exceptTxid: String? = null): HeldOutpoints {
    val stored = cachedStoredHolds
    return HeldOutpoints(reservedOutpoints(exceptTxid, stored.orEmpty()), complete = stored != null)
}

/**
 * Unconfirmed stored sends as (txid, outpoint key). Each clear installs a new slot, and a read only
 * publishes into the slot it started from, so a read that raced a clear cannot bring back stale holds.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StoredHoldsCache(
    private val load: () -> List<Pair<String, String>>,
) {
    private class Slot(
        val holds: List<Pair<String, String>>?,
    )

    private val slot = AtomicReference(Slot(null))

    fun clear() {
        slot.store(Slot(null))
    }

    fun peek(): List<Pair<String, String>>? = slot.load().holds

    /** A load that raced a clear may predate the write that caused it, so it is read again. */
    fun get(): List<Pair<String, String>> {
        while (true) {
            val cur = slot.load()
            cur.holds?.let { return it }
            val fresh = load()
            if (slot.compareAndSet(cur, Slot(fresh))) return fresh
        }
    }
}

internal fun loadStoredHolds(db: Database): List<Pair<String, String>> =
    db.sends
        .list()
        .filter { it.confirmedInBlock == 0L }
        .flatMap { row -> row.coins.map { row.txid to outpointKey(it.txid, it.vout) } } +
        db.privateSends
            .list()
            .filter { it.confirmedInBlock == 0L }
            .flatMap { row -> row.coins.map { row.txid to outpointKey(it.txid, it.vout) } }

@OptIn(ExperimentalAtomicApi::class)
private fun <T> putPending(
    map: AtomicReference<Map<String, T>>,
    id: String,
    row: T,
) {
    while (true) {
        val cur = map.load()
        if (map.compareAndSet(cur, cur + (id to row))) return
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun <T> removePending(
    map: AtomicReference<Map<String, T>>,
    id: String,
) {
    while (true) {
        val cur = map.load()
        if (id !in cur || map.compareAndSet(cur, cur - id)) return
    }
}
