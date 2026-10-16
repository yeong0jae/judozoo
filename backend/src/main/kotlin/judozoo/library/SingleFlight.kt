package judozoo.library

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * 같은 키로 동시에 들어온 호출을 하나로 합친다(single-flight).
 *
 * [TtlCache]는 이 일을 캐시 안에서 한다. 분봉 저장소처럼 **통째로 버리지 않고 새 봉만 이어 붙이는** 곳은
 * 캐시를 쓸 수 없어 따로 둔다 — 같은 종목을 여러 명이 동시에 열면 각자 브로커를 불렀다.
 *
 * 선두 스레드만 부르고, 뒤따라온 스레드는 **락이 아니라 결과를** 기다린다. 선두가 실패하면 같은 예외를 함께 받는다.
 */
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
