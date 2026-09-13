package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun basePeer(
    host: String = "1.2.3.4",
    port: Int = 8333,
    services: ULong = 0uL,
    alive: Boolean = false,
    usedForBlocks: Boolean = false,
    lastProbedAt: Long? = null,
) = PeerWrite(
    host = host,
    port = port,
    services = services,
    alive = alive,
    usedForBlocks = usedForBlocks,
    lastProbedAt = lastProbedAt,
)

class PeersTest {
    @Test
    fun high_service_bit_survives_upsert_and_service_filters() {
        val db = createSqliteDatabase(":memory:")
        val high = 1uL shl 63
        db.peers.upsert(basePeer(host = "9.9.9.9", services = high or 64uL, alive = true))
        assertEquals(high or 64uL, db.peers.list()[0].services)
        assertEquals(
            listOf("9.9.9.9"),
            db.peers.listAliveWithServices(high, 10).map { it.host },
        )
        assertTrue(
            db.peers
                .listWithServices(64uL, 10)
                .map { it.host }
                .contains("9.9.9.9"),
        )
        db.close()
    }

    @Test
    fun upsert_round_trip_and_count() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(0, db.peers.count())
        db.peers.upsert(basePeer(services = 2049uL))
        assertEquals(1, db.peers.count())
        val peer = db.peers.list().single()
        assertEquals("1.2.3.4", peer.host)
        assertEquals(8333, peer.port)
        assertEquals(2049uL, peer.services)
        assertEquals(false, peer.alive)
        assertEquals(false, peer.usedForBlocks)
        assertEquals(null, peer.lastProbedAt)
        db.close()
    }

    @Test
    fun gossip_cannot_replace_services_verified_by_a_probe() {
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(basePeer(services = 1uL))
        db.peers.markAlive("1.2.3.4", 8333, true)
        db.peers.markProbed("1.2.3.4", 8333, 42)
        db.peers.upsert(
            basePeer(
                services = 9uL,
                alive = false,
                usedForBlocks = true,
                lastProbedAt = null,
            ),
        )
        assertEquals(1, db.peers.count())
        val peer = db.peers.list().single()
        assertEquals(1uL, peer.services)
        assertEquals(true, peer.alive)
        assertEquals(42L, peer.lastProbedAt)
        assertEquals(false, peer.usedForBlocks)
        db.close()
    }

    @Test
    fun unprobed_hints_and_verified_probes_can_refresh_services() {
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(basePeer(services = 1uL))
        db.peers.upsert(basePeer(services = 9uL))
        assertEquals(
            9uL,
            db.peers
                .list()
                .single()
                .services,
        )

        db.peers.markProbed("1.2.3.4", 8333, 42)
        db.peers.upsert(basePeer(services = 64uL, lastProbedAt = 42))
        assertEquals(
            64uL,
            db.peers
                .list()
                .single()
                .services,
        )
        db.close()
    }

    @Test
    fun conflict_upsert_with_services_zero_preserves_known_bits() {
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(basePeer(services = 64uL, alive = true))
        db.peers.upsert(basePeer(services = 0uL, alive = false))
        val peer = db.peers.list().single()
        assertEquals(64uL, peer.services)
        assertEquals(true, peer.alive)
        assertEquals(
            listOf("1.2.3.4"),
            db.peers.listAliveWithServices(64uL, 10).map { it.host },
        )
        db.close()
    }

    @Test
    fun listAlive_and_mark_helpers() {
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(basePeer(host = "1.1.1.1", alive = true))
        db.peers.upsert(basePeer(host = "2.2.2.2", alive = false))
        assertEquals(listOf("1.1.1.1"), db.peers.listAlive().map { it.host })
        db.peers.markProbed("2.2.2.2", 8333, 1000)
        db.peers.markAlive("2.2.2.2", 8333, true)
        val probed = db.peers.list().single { it.host == "2.2.2.2" }
        assertEquals(1000L, probed.lastProbedAt)
        assertEquals(true, probed.alive)
        db.peers.markUsedForBlocks("1.1.1.1", 8333)
        assertEquals(
            true,
            db.peers
                .list()
                .single { it.host == "1.1.1.1" }
                .usedForBlocks,
        )
        db.close()
    }

    @Test
    fun listAliveWithServices_filters_by_bits_and_unusedForBlocks() {
        val db = createSqliteDatabase(":memory:")
        val net = 1uL
        db.peers.upsert(basePeer(host = "1.1.1.1", services = net, alive = true, usedForBlocks = false))
        db.peers.upsert(basePeer(host = "2.2.2.2", services = net, alive = true, usedForBlocks = true))
        db.peers.upsert(basePeer(host = "3.3.3.3", services = 0uL, alive = true, usedForBlocks = false))
        db.peers.upsert(basePeer(host = "4.4.4.4", services = net, alive = false, usedForBlocks = false))

        assertEquals(
            listOf("1.1.1.1", "2.2.2.2"),
            db.peers.listAliveWithServices(net, 10).map { it.host },
        )
        assertEquals(
            listOf("1.1.1.1"),
            db.peers
                .listAliveWithServices(net, 10, AliveServiceOptions(unusedForBlocks = true))
                .map { it.host },
        )
        db.close()
    }

    @Test
    fun listWithServices_prefers_alive_and_listProbeQueue_orders_never_probed_first() {
        val db = createSqliteDatabase(":memory:")
        val cf = 64uL
        db.peers.upsert(basePeer(host = "1.1.1.1", services = cf, alive = false, lastProbedAt = 10))
        db.peers.upsert(basePeer(host = "2.2.2.2", services = cf, alive = true, lastProbedAt = 20))
        db.peers.upsert(basePeer(host = "3.3.3.3", services = 0uL, alive = true, lastProbedAt = null))
        db.peers.upsert(basePeer(host = "4.4.4.4", services = cf, alive = false, lastProbedAt = null))

        assertEquals(
            listOf("2.2.2.2", "4.4.4.4", "1.1.1.1"),
            db.peers.listWithServices(cf, 10).map { it.host },
        )
        assertEquals(
            listOf("3.3.3.3", "4.4.4.4"),
            db.peers.listProbeQueue(2).map { it.host },
        )
        db.close()
    }
}
