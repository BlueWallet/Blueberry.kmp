package io.bluewallet.blueberry.bus

/** Module lifecycle and short incident notes for `module:status`. */
enum class ModuleStatus(val wireName: String) {
    STARTING("starting"),
    RUNNING("running"),
    STOPPED("stopped"),
    ERROR("error"),
}

/** Peer work kind for `peers:sockets` counts. */
enum class PeerSocketKind(val wireName: String) {
    PROBE("probe"),
    HDR("hdr"),
    FILT("filt"),
    BLK("blk"),
}

/** Why the sync evaluator left idle. */
enum class SyncCatchupReason(val wireName: String) {
    HEADERS("headers"),
    FILTERS("filters"),
    BLOCKS("blocks"),
    PEERS("peers"),
}

/**
 * Broadcast job progress phase.
 *
 * Final outcome still arrives on `broadcast:done`.
 */
enum class BroadcastPhase(val wireName: String) {
    WAITING_PEERS("waiting-peers"),
    ATTEMPT("attempt"),
    FAILED_ATTEMPT("failed-attempt"),
    ERROR("error"),
}

/** One module status update. */
data class ModuleStatusPayload(
    val module: String,
    val status: ModuleStatus,
    val detail: String? = null,
)

/** Recount known peers from SQLite. */
data class PeersUpdatedPayload(val at: Long)

/**
 * Active peer work for one kind.
 *
 * [open] is the producer's in-flight count (jobs or probes).
 * It may include work before a TCP socket is open.
 *
 * The peer store applies kind/open. Not in SQLite.
 */
data class PeersSocketsPayload(
    val at: Long,
    val kind: PeerSocketKind,
    val open: Int,
)

/**
 * Header sync snapshot.
 *
 * [downloaded] / [total] are heights from the sync checkpoint to tip /
 * peer tip, not counts from the current run only.
 *
 * The headers store re-reads height and downloaded from SQLite.
 * Apply [total] only if [total] > 0.
 */
data class HeadersProgressPayload(
    val at: Long,
    val downloaded: Int,
    val total: Int,
    /** Local header tip height. */
    val height: Int,
)

/**
 * Compact-filter download snapshot.
 *
 * [total] is the active birthday-to-tip range when known.
 * Wake listeners may ignore the payload and re-read the DB.
 *
 * The filters store re-reads downloaded from SQLite.
 * Apply [total] only if [total] > 0.
 */
data class FiltersProgressPayload(
    val at: Long,
    val downloaded: Int,
    val total: Int,
)

/**
 * One filter hit the wallet watchlist.
 *
 * The DB row is already inserted before emit. Payload is optional context;
 * many listeners only use this as a wake signal.
 *
 * The blocks store refreshes downloaded/matched from SQLite.
 */
data class FiltersMatchPayload(
    val height: Int,
    val blockHashInternalHex: String,
)

/**
 * Filter scan progress against the wallet.
 *
 * [scanned] is filters already checked (`countScanned`), not hit count.
 *
 * The matching store re-reads scanned/total from SQLite.
 * Ignore payload counts.
 */
data class MatchingProgressPayload(
    val at: Long,
    val scanned: Int,
    val total: Int,
)

/**
 * Full-block download snapshot.
 *
 * [matched] is known matched-block rows; [downloaded] is stored full blocks.
 *
 * The blocks store re-reads downloaded/matched from SQLite.
 * Ignore payload counts.
 * The wallet store refreshes parse-backlog counts and does not rebuild
 * the tx list. The matching store also re-reads scanned/total.
 */
data class BlocksProgressPayload(
    val at: Long,
    val downloaded: Int,
    val matched: Int,
)

/** Sync evaluator entered idle (settled). */
data class SyncIdlePayload(val at: Long)

/** Sync evaluator left idle; resume catch-up work. */
data class SyncCatchupPayload(
    val at: Long,
    val reason: SyncCatchupReason,
)

/**
 * Wallet tx UI should refresh.
 *
 * Emitted after parse/update work. The set may be unchanged.
 *
 * The wallet store rebuilds its snapshot from SQLite.
 */
data class WalletTxsPayload(val at: Long)

/** Start broadcasting one raw transaction. */
data class BroadcastRequestPayload(
    val id: String,
    val txHex: String,
)

/** Cancel the broadcast job with this [id], if it is active. */
data class BroadcastCancelPayload(val id: String)

/**
 * Broadcast job progress.
 *
 * Phases: `waiting-peers`, `attempt`, `failed-attempt`, `error`.
 * Final outcome still arrives on `broadcast:done`.
 */
data class BroadcastProgressPayload(
    val id: String,
    val phase: BroadcastPhase,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val peer: String? = null,
    val detail: String? = null,
)

/**
 * Broadcast job finished.
 *
 * [Ok] means the peer did not reject the tx. Timeout or disconnect
 * without a reject also counts as success on this path.
 */
sealed class BroadcastDonePayload {
    abstract val id: String

    data class Ok(override val id: String, val peer: String) : BroadcastDonePayload()

    data class Error(override val id: String, val error: String) : BroadcastDonePayload()
}

/**
 * Typed in-process event catalog.
 *
 * Durable facts live in SQLite. UI stores hydrate those into memory at start
 * and on wake. Session facts (open sockets, peer/filter range total, broadcast,
 * module status, sync idle) live in payloads; stores apply those fields.
 *
 * `at` fields are Unix milliseconds.
 *
 * Each [name] matches helix3 `EventMap` keys.
 */
sealed class Event<T>(val name: String) {
    /**
     * One module status update.
     *
     * Used for start/run/stop and also for short incident notes.
     * Not every module emits every status value.
     */
    data object ModuleStatus : Event<ModuleStatusPayload>("module:status")

    /** Wake: recount known peers from SQLite. */
    data object PeersUpdated : Event<PeersUpdatedPayload>("peers:updated")

    /**
     * Active peer work for one kind.
     *
     * `open` is the producer's in-flight count (jobs or probes).
     * It may include work before a TCP socket is open.
     *
     * The peer store applies kind/open. Not in SQLite.
     */
    data object PeersSockets : Event<PeersSocketsPayload>("peers:sockets")

    /**
     * Header sync snapshot.
     *
     * `downloaded` / `total` are heights from the sync checkpoint to tip /
     * peer tip, not counts from the current run only.
     *
     * The headers store re-reads height and downloaded from SQLite.
     * Apply `total` only if `total` > 0.
     */
    data object HeadersProgress : Event<HeadersProgressPayload>("headers:progress")

    /**
     * Compact-filter download snapshot.
     *
     * `total` is the active birthday-to-tip range when known.
     * Wake listeners may ignore the payload and re-read the DB.
     *
     * The filters store re-reads downloaded from SQLite.
     * Apply `total` only if `total` > 0.
     */
    data object FiltersProgress : Event<FiltersProgressPayload>("filters:progress")

    /**
     * One filter hit the wallet watchlist.
     *
     * The DB row is already inserted before emit. Payload is optional context;
     * many listeners only use this as a wake signal.
     *
     * The blocks store refreshes downloaded/matched from SQLite.
     */
    data object FiltersMatch : Event<FiltersMatchPayload>("filters:match")

    /**
     * Filter scan progress against the wallet.
     *
     * `scanned` is filters already checked (`countScanned`), not hit count.
     *
     * The matching store re-reads scanned/total from SQLite.
     * Ignore payload counts.
     */
    data object MatchingProgress : Event<MatchingProgressPayload>("matching:progress")

    /**
     * Full-block download snapshot.
     *
     * `matched` is known matched-block rows; `downloaded` is stored full blocks.
     *
     * The blocks store re-reads downloaded/matched from SQLite.
     * Ignore payload counts.
     * The wallet store refreshes parse-backlog counts and does not rebuild
     * the tx list. The matching store also re-reads scanned/total.
     */
    data object BlocksProgress : Event<BlocksProgressPayload>("blocks:progress")

    /** Sync evaluator entered idle (settled). */
    data object SyncIdle : Event<SyncIdlePayload>("sync:idle")

    /** Sync evaluator left idle; resume catch-up work. */
    data object SyncCatchup : Event<SyncCatchupPayload>("sync:catchup")

    /**
     * Wallet tx UI should refresh.
     *
     * Emitted after parse/update work. The set may be unchanged.
     *
     * The wallet store rebuilds its snapshot from SQLite.
     */
    data object WalletTxs : Event<WalletTxsPayload>("wallet:txs")

    /** Start broadcasting one raw transaction. */
    data object BroadcastRequest : Event<BroadcastRequestPayload>("broadcast:request")

    /** Cancel the broadcast job with this `id`, if it is active. */
    data object BroadcastCancel : Event<BroadcastCancelPayload>("broadcast:cancel")

    /**
     * Broadcast job progress.
     *
     * Phases: `waiting-peers`, `attempt`, `failed-attempt`, `error`.
     * Final outcome still arrives on `broadcast:done`.
     */
    data object BroadcastProgress : Event<BroadcastProgressPayload>("broadcast:progress")

    /**
     * Broadcast job finished.
     *
     * [BroadcastDonePayload.Ok] means the peer did not reject the tx.
     * Timeout or disconnect without a reject also counts as success
     * on this path.
     */
    data object BroadcastDone : Event<BroadcastDonePayload>("broadcast:done")
}

/**
 * In-process typed pub/sub.
 *
 * `emit` calls handlers in the same turn. Handlers must be synchronous;
 * thrown errors are swallowed. `on` returns an unsubscribe function.
 * `on` / `emit` / unsubscribe are safe to call from concurrent module loops.
 */
interface MessageBus {
    fun <T> on(event: Event<T>, handler: (T) -> Unit): () -> Unit

    fun <T> emit(event: Event<T>, payload: T)
}
