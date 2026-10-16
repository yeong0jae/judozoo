package judozoo.library

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TtlCache<K : Any, V>(
    val name: String,
    private val ttl: () -> Duration,
    private val maxSize: Int = 100,
    private val skipIf: (V) -> Boolean = { false },
    private val serveStale: Boolean = false,
    private val ticker: () -> Long = System::nanoTime,
) {
    constructor(
        name: String,
        ttl: Duration,
        maxSize: Int = 100,
        skipIf: (V) -> Boolean = { false },
        serveStale: Boolean = false,
        ticker: () -> Long = System::nanoTime,
    ) : this(name, { ttl }, maxSize, skipIf, serveStale, ticker)

    private class Entry<V>(val expiresAt: Long, val value: V)

    private val lock = ReentrantLock()
    private val entries = lru<Entry<V>>()
    private val retained = lru<V>()
    private val inflight = HashMap<K, CompletableFuture<V>>()

    fun get(key: K, load: () -> V): V = call(key, force = false, load)

    fun refresh(key: K, load: () -> V): V = call(key, force = true, load)

    fun peek(key: K): V? = lock.withLock {
        if (serveStale) retained[key] else entries[key]?.takeIf { it.expiresAt > ticker() }?.value
    }

    private fun call(key: K, force: Boolean, load: () -> V): V {
        val (pending, leader) = lock.withLock {
            if (!force) {
                entries[key]?.let { if (it.expiresAt > ticker()) return it.value else entries.remove(key) }
                if (serveStale && retained.containsKey(key)) {
                    @Suppress("UNCHECKED_CAST")
                    return retained[key] as V
                }
            }
            inflight[key]?.let { it to false } ?: (CompletableFuture<V>().also { inflight[key] = it } to true)
        }

        if (!leader) {
            try {
                return pending.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        val result = try {
            load()
        } catch (e: Throwable) {
            lock.withLock { inflight.remove(key) }
            pending.completeExceptionally(e)
            throw e
        }

        val store = !skipIf(result)
        val expiresAt = if (store) ticker() + ttl().toNanos() else 0
        lock.withLock {
            if (store) {
                entries[key] = Entry(expiresAt, result)
                if (serveStale) retained[key] = result
            }
            inflight.remove(key)
        }
        pending.complete(result)
        return result
    }

    private fun <T> lru() = object : LinkedHashMap<K, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, T>) = size > maxSize
    }

    companion object {
        fun isEmpty(result: Any?): Boolean = when (result) {
            null -> true
            is Collection<*> -> result.isEmpty()
            is Map<*, *> -> result.isEmpty()
            is CharSequence -> result.isEmpty()
            else -> false
        }
    }
}
