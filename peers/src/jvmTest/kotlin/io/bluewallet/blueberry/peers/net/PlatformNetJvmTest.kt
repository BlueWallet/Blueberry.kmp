package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class PlatformNetJvmTest {
    @Test
    fun connect_round_trips_bytes_on_localhost() =
        runBlocking {
            val server = ServerSocket(0)
            val port = server.localPort
            val serverJob =
                launch(Dispatchers.IO) {
                    server.use { ss ->
                        ss.accept().use { sock ->
                            val buf = ByteArray(4)
                            var n = 0
                            while (n < 4) {
                                val r = sock.getInputStream().read(buf, n, 4 - n)
                                if (r < 0) break
                                n += r
                            }
                            sock.getOutputStream().write(buf)
                            sock.getOutputStream().flush()
                        }
                    }
                }
            val net = createPlatformNet()
            val duplex = net.connect("127.0.0.1", port)
            duplex.write(byteArrayOf(1, 2, 3, 4))
            val got = ByteArray(4)
            var offset = 0
            while (offset < 4) {
                val chunk = duplex.read(4 - offset)
                check(chunk.isNotEmpty())
                chunk.copyInto(got, offset)
                offset += chunk.size
            }
            duplex.close()
            serverJob.join()
            assertContentEquals(byteArrayOf(1, 2, 3, 4), got)
        }

    @Test
    fun handshake_timeout_unblocks_silent_accepted_peer() {
        val server = ServerSocket(0)
        val port = server.localPort
        val acceptor =
            Thread {
                try {
                    server.use { ss ->
                        ss.accept().use { Thread.sleep(30_000) }
                    }
                } catch (_: Throwable) {
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        val elapsedMs = AtomicLong(-1)
        val error = AtomicReference<String?>(null)
        val probe =
            Thread {
                runBlocking {
                    val started = System.nanoTime()
                    val result =
                        probePeer(
                            "127.0.0.1",
                            port,
                            ProbeOptions(
                                timeoutMs = 250,
                                connect = createPlatformNet().connect,
                            ),
                        )
                    elapsedMs.set((System.nanoTime() - started) / 1_000_000)
                    error.set((result as? ProbeResult.Err)?.error ?: result.toString())
                }
            }.also { it.start() }
        probe.join(3_000)
        runCatching { server.close() }
        acceptor.interrupt()
        assertTrue(!probe.isAlive, "probePeer still blocked after 3s; error=${error.get()}")
        assertTrue(error.get()?.contains("timed out") == true, error.get())
        assertTrue(elapsedMs.get() in 1..2_000, "elapsed=${elapsedMs.get()}ms")
    }

    @Test
    fun dns_resolve4_localhost_is_loopback() =
        runBlocking {
            val net = createPlatformNet()
            val v4 = net.dns.resolve4("localhost")
            assertTrue(v4.contains("127.0.0.1") || v4.any { it.startsWith("127.") })
        }
}
