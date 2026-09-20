package io.bluewallet.blueberry

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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

    @Volatile var wallet: Wallet? = null
        private set

    @Volatile private var pendingSendRow: SendRow? = null

    @Volatile private var pendingPrivateSendRow: PrivateSendRow? = null

    fun armSendPersist(row: SendRow) {
        pendingSendRow = row
        pendingPrivateSendRow = null
    }

    fun armPrivateSendPersist(row: PrivateSendRow) {
        pendingPrivateSendRow = row
        pendingSendRow = null
    }

    fun persistArmedBroadcast() {
        val snap = broadcastStore.get()
        val send = pendingSendRow
        val priv = pendingPrivateSendRow
        val wroteSend = send != null && persistSendAndRefresh(db, walletTxsStore, wallet, send, snap)
        val wrotePriv = priv != null && persistPrivateSendAndRefresh(db, walletTxsStore, wallet, priv, snap)
        pendingSendRow = armedAfterPersist(pendingSendRow, send, wroteSend)
        pendingPrivateSendRow = armedAfterPersist(pendingPrivateSendRow, priv, wrotePriv)
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
        val unbindBroadcast = bindBroadcastEvents(bus, broadcastStore) { persistArmedBroadcast() }
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
