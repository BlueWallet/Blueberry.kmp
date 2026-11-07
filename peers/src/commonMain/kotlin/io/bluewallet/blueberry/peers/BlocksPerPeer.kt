package io.bluewallet.blueberry.peers

import io.bluewallet.blueberry.storage.Database

const val BLOCKS_PER_PEER_KEY = "max_blocks_per_peer"
const val DEFAULT_BLOCKS_PER_PEER = 1
const val MAX_BLOCKS_PER_PEER = 10

/** Read at peer selection time so changes do not require restarting sync. */
fun loadBlocksPerPeer(db: Database): Int =
    db.keyValue
        .get(BLOCKS_PER_PEER_KEY)
        ?.toIntOrNull()
        ?.takeIf { it in DEFAULT_BLOCKS_PER_PEER..MAX_BLOCKS_PER_PEER }
        ?: DEFAULT_BLOCKS_PER_PEER

fun saveBlocksPerPeer(
    db: Database,
    value: Int,
) {
    db.keyValue.set(
        BLOCKS_PER_PEER_KEY,
        value.coerceIn(DEFAULT_BLOCKS_PER_PEER, MAX_BLOCKS_PER_PEER).toString(),
    )
}
