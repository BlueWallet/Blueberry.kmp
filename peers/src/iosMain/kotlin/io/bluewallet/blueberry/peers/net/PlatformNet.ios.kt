package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.IPPROTO_TCP
import platform.posix.NI_NUMERICHOST
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.TCP_NODELAY
import platform.posix.addrinfo
import platform.posix.connect
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.strerror
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(DelicateCoroutinesApi::class)
private val nativeDispatcher = newFixedThreadPoolContext(64, "peers-io")
private val ioScope = CoroutineScope(nativeDispatcher + SupervisorJob())

@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformNet(): PlatformNet =
    PlatformNet(
        connect = { host, port -> connectSocket(host, port) },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String): List<String> = withContext(nativeDispatcher) { resolveFamily(host, AF_INET) }

                override suspend fun resolve6(host: String): List<String> = withContext(nativeDispatcher) { resolveFamily(host, AF_INET6) }
            },
    )

@OptIn(ExperimentalForeignApi::class)
private fun posixError(op: String): String {
    val err = errno
    val msg = strerror(err)?.toKString() ?: err.toString()
    return "$op failed: $msg"
}

@OptIn(ExperimentalForeignApi::class)
private fun numericHost(ai: addrinfo): String? =
    memScoped {
        val sa = ai.ai_addr ?: return null
        val buf = allocArray<ByteVar>(1025)
        val rc =
            getnameinfo(
                sa,
                ai.ai_addrlen,
                buf,
                1025.convert(),
                null,
                0.convert(),
                NI_NUMERICHOST,
            )
        if (rc == 0) buf.toKString() else null
    }

@OptIn(ExperimentalForeignApi::class)
private fun resolveFamily(
    host: String,
    family: Int,
): List<String> =
    memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = family
        hints.ai_socktype = SOCK_STREAM
        val result = alloc<CPointerVar<addrinfo>>()
        val rc = getaddrinfo(host, null, hints.ptr, result.ptr)
        if (rc != 0) return emptyList()
        try {
            val out = mutableListOf<String>()
            var current: addrinfo? = result.pointed
            while (current != null) {
                numericHost(current)?.let { out.add(it) }
                current = current.ai_next?.pointed
            }
            out
        } finally {
            freeaddrinfo(result.value)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private class CopiedAddr(
    val family: Int,
    val socktype: Int,
    val protocol: Int,
    val addr: ByteArray,
)

@OptIn(ExperimentalForeignApi::class)
private fun lookupCopiedAddrs(
    host: String,
    port: Int,
): List<CopiedAddr> =
    memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        val result = alloc<CPointerVar<addrinfo>>()
        val rc = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
        if (rc != 0) {
            val msg = gai_strerror(rc)?.toKString() ?: "getaddrinfo $rc"
            throw IllegalStateException(msg)
        }
        try {
            val out = mutableListOf<CopiedAddr>()
            var current: addrinfo? = result.pointed
            while (current != null) {
                val sa = current.ai_addr
                val len = current.ai_addrlen.toInt()
                if (sa != null && len > 0) {
                    val bytes = ByteArray(len)
                    bytes.usePinned { dst ->
                        memcpy(dst.addressOf(0), sa, len.convert())
                    }
                    out.add(
                        CopiedAddr(
                            family = current.ai_family,
                            socktype = current.ai_socktype,
                            protocol = current.ai_protocol,
                            addr = bytes,
                        ),
                    )
                }
                current = current.ai_next?.pointed
            }
            out
        } finally {
            freeaddrinfo(result.value)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun enableSocketOptions(fd: Int) =
    memScoped {
        val yes = alloc<IntVar>()
        yes.value = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, yes.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, yes.ptr, sizeOf<IntVar>().convert())
    }

@OptIn(ExperimentalForeignApi::class)
private suspend fun connectFd(
    fd: Int,
    addr: ByteArray,
): Int {
    val job =
        ioScope.async {
            addr.usePinned { pinned ->
                connect(
                    fd,
                    pinned.addressOf(0).reinterpret<sockaddr>(),
                    addr.size.convert(),
                )
            }
        }
    try {
        return job.await()
    } catch (e: CancellationException) {
        platform.posix.close(fd)
        withContext(NonCancellable) { job.join() }
        throw e
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun connectSocket(
    host: String,
    port: Int,
): ByteDuplex {
    val addrs = withContext(nativeDispatcher) { lookupCopiedAddrs(host, port) }
    var lastError = "connect failed"
    for (ai in addrs) {
        val fd = socket(ai.family, ai.socktype, ai.protocol)
        if (fd < 0) {
            lastError = posixError("socket")
            continue
        }
        val rc = connectFd(fd, ai.addr)
        if (rc == 0) {
            enableSocketOptions(fd)
            return PosixByteDuplex(fd)
        }
        lastError = posixError("connect")
        platform.posix.close(fd)
    }
    throw IllegalStateException(lastError)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class PosixByteDuplex(
    initialFd: Int,
) : ByteDuplex {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val fd = AtomicInt(initialFd)

    private suspend fun <T> abortableIo(
        current: Int,
        block: () -> T,
    ): T {
        val job = ioScope.async { block() }
        try {
            return job.await()
        } finally {
            if (!job.isCompleted) {
                shutdown(current, SHUT_RDWR)
                withContext(NonCancellable) { job.join() }
            }
        }
    }

    override suspend fun read(n: Int): ByteArray =
        readMutex.withLock {
            val current = fd.load()
            if (current < 0) return@withLock ByteArray(0)
            abortableIo(current) {
                val buf = ByteArray(n)
                val r =
                    buf.usePinned { pinned ->
                        platform.posix.read(current, pinned.addressOf(0), n.convert())
                    }
                when {
                    r < 0 -> throw IllegalStateException(posixError("read"))
                    r == 0L -> ByteArray(0)
                    else -> buf.copyOf(r.toInt())
                }
            }
        }

    override suspend fun write(bytes: ByteArray) =
        writeMutex.withLock {
            val current = fd.load()
            check(current >= 0) { "socket closed" }
            abortableIo(current) {
                var offset = 0
                bytes.usePinned { pinned ->
                    while (offset < bytes.size) {
                        val n =
                            platform.posix.write(
                                current,
                                pinned.addressOf(offset),
                                (bytes.size - offset).convert(),
                            )
                        if (n <= 0) throw IllegalStateException(posixError("write"))
                        offset += n.toInt()
                    }
                }
            }
        }

    override suspend fun close() {
        val current = fd.exchange(-1)
        if (current < 0) return
        shutdown(current, SHUT_RDWR)
        withContext(NonCancellable) {
            readMutex.withLock {
                writeMutex.withLock {
                    platform.posix.close(current)
                }
            }
        }
    }
}
