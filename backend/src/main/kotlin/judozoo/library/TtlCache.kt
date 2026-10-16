package judozoo.library

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TTL 캐시. Python `library/cache.py`의 `ttl_cache`와 같은 시맨틱이다.
 *
 * - **빈 응답을 캐싱하면 안 되는** 경우는 [skipIf]로 거른다 — 빈 목록을 캐싱하면 후속 폴링이 TTL 동안 빈 결과를 재사용한다
 * - **만료 순간의 중복 호출은 합친다(single-flight).** 각자 브로커를 부르면 같은 값을 N번 받고 리미터 앞에 스레드 N개가 묶인다
 * - [refresh]는 캐시를 보지 않고 새로 불러 갈아 끼운다 — 미리 채우는 폴러가 쓴다. 실패하면 기존 값을 건드리지 않는다
 * - [ttl]은 넣는 순간마다 불러 그 항목의 수명으로 쓴다. 락 밖에서 부른다 — 휴장 판정처럼 외부를 탈 수 있다
 * - [serveStale]은 폴러 전용. 마지막 정상값은 만료 후에도 돌려주고 [refresh]만 갱신한다. 콜드 스타트에는 요청도 조회한다
 */
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

    /** 부르지 않고 지금 들고 있는 값. */
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

        // 뒤따라온 스레드는 부르지 않고 선두의 결과를 받는다. 선두가 실패하면 같은 예외를 함께 받는다 —
        // 재시도는 호출부가 정할 일이다
        if (!leader) {
            try {
                return pending.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        // 계산은 락 밖에서 한다. 외부 API 호출을 락 안에서 붙잡으면 느린 브로커 하나가 스레드를 말린다
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

    /** 최대 크기를 넘으면 가장 오래 안 쓴 항목부터 밀려난다. */
    private fun <T> lru() = object : LinkedHashMap<K, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, T>) = size > maxSize
    }

    companion object {
        /** [skipIf]로 자주 쓰는 조건 — null이거나 비어 있으면 캐싱하지 않는다. */
        fun isEmpty(result: Any?): Boolean = when (result) {
            null -> true
            is Collection<*> -> result.isEmpty()
            is Map<*, *> -> result.isEmpty()
            is CharSequence -> result.isEmpty()
            else -> false
        }
    }
}
