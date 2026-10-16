package judozoo.library

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

class SingleFlight<K : Any, V> {
    private val inflight = ConcurrentHashMap<K, CompletableFuture<V>>()

    fun run(key: K, call: () -> V): V {
        val mine = CompletableFuture<V>()
        val pending = inflight.putIfAbsent(key, mine)
        if (pending != null) {
            try {
                return pending.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            return call().also { inflight.remove(key); mine.complete(it) }
        } catch (e: Throwable) {
            inflight.remove(key)
            mine.completeExceptionally(e)
            throw e
        }
    }
}
