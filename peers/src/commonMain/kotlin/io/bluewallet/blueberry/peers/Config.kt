package io.bluewallet.blueberry.peers

object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerAddrTimeoutMs: Long = 3_000
    const val peerCrawlIntervalMs: Long = 15_000
    const val peerConcurrency: Int = 30
    const val headerSyncTimeoutMs: Long = 30_000
    const val headerRacePeers: Int = 10
    /** At-tip health check / missed-block poll. One Bitcoin block time. */
    const val headerIdleCheckMs: Long = 10 * 60 * 1_000L
    const val filterSyncTimeoutMs: Long = 30_000
    const val filterConcurrency: Int = 10
    const val filterHeaderBatchSize: Int = 2000
    const val filterBatchSize: Int = 100
    const val blockConnectTimeoutMs: Long = 3_000
    const val blockSyncTimeoutMs: Long = 30_000
    const val blockConcurrency: Int = 10
}
