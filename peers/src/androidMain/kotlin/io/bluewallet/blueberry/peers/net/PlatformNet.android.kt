package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

actual fun createPlatformNet(): PlatformNet =
    PlatformNet(
        connect = { host, port -> connectSocket(host, port) },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String): List<String> =
                    withContext(Dispatchers.IO) {
                        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                    }

                override suspend fun resolve6(host: String): List<String> =
                    withContext(Dispatchers.IO) {
                        InetAddress.getAllByName(host).filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }
                    }
            },
    )

private suspend fun connectSocket(
    host: String,
    port: Int,
): ByteDuplex {
    val socket = Socket()
    try {
        coroutineScope {
            val job =
                launch(Dispatchers.IO) {
                    socket.connect(
                        InetSocketAddress(host, port),
                        Config.peerRetryProbeTimeoutMs.toInt(),
                    )
                    socket.tcpNoDelay = true
                }
            try {
                job.join()
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            }
        }
        check(!socket.isClosed) { "connect aborted" }
        return SocketByteDuplex(socket)
    } catch (e: Throwable) {
        runCatching { socket.close() }
        throw e
    }
}

private class SocketByteDuplex(
    private val socket: Socket,
) : ByteDuplex {
    private val mutex = Mutex()

    private suspend fun <T> abortableIo(block: () -> T): T {
        val job = ioScope.async { block() }
        try {
            return job.await()
        } catch (e: CancellationException) {
            runCatching { socket.close() }
            withContext(NonCancellable) { job.join() }
            throw e
        }
    }

    override suspend fun read(n: Int): ByteArray =
        mutex.withLock {
            abortableIo {
                if (socket.isClosed) {
                    ByteArray(0)
                } else {
                    val buf = ByteArray(n)
                    val r = socket.getInputStream().read(buf)
                    if (r <= 0) ByteArray(0) else buf.copyOf(r)
                }
            }
        }

    override suspend fun write(bytes: ByteArray) =
        mutex.withLock {
            abortableIo {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
        }

    override suspend fun close() {
        runCatching { socket.close() }
    }
}
