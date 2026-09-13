package io.bluewallet.blueberry.bus

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun createMessageBus(): MessageBus {
    val listeners = AtomicReference<Map<Event<*>, Set<Any>>>(emptyMap())

    return object : MessageBus {
        override fun <T> on(
            event: Event<T>,
            handler: (T) -> Unit,
        ): () -> Unit {
            while (true) {
                val cur = listeners.load()
                val next = cur + (event to (cur[event].orEmpty() + handler))
                if (listeners.compareAndSet(cur, next)) break
            }
            return {
                while (true) {
                    val cur = listeners.load()
                    val set = cur[event].orEmpty() - handler
                    val next = if (set.isEmpty()) cur - event else cur + (event to set)
                    if (listeners.compareAndSet(cur, next)) break
                }
            }
        }

        override fun <T> emit(
            event: Event<T>,
            payload: T,
        ) {
            val snapshot = listeners.load()[event] ?: return
            for (handler in snapshot) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (handler as (T) -> Unit)(payload)
                } catch (_: Throwable) {
                    // isolate subscriber failures
                }
            }
        }
    }
}
