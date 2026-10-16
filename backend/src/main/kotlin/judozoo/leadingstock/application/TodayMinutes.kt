package judozoo.leadingstock.application

import judozoo.leadingstock.application.StockMinuteArchive.Companion.baseCode
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import judozoo.library.SingleFlight
import judozoo.platform.toss.TossCandles
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap

/**
 * 당일 1분봉 저장소 — 종목별 오늘 분봉을 들고 있다가 **마지막 봉 이후만** 받아 뒤에 이어 붙인다.
 *
 * TTL 캐시는 만료마다 오늘치(최대 720봉)를 통째로 다시 받고, 그 틈에 온 요청이 브로커를 기다린다.
 *
 * 채우는 쪽은 둘이다.
 * - 감시 풀: 장중 20초마다 [sync]가 미리 채운다.
 * - 그 밖의 종목(상세 화면으로 연 종목): 읽을 때 [ensure]가 낡았으면 그 자리에서 이어 받는다.
 *
 * **얼마나 새것이어야 하는지는 읽는 쪽이 정한다**(`freshSince`). 진행 중인 봉은 값이 바뀌므로 마지막 몇 봉은 다시 받아 **덮어쓴다**.
 */
@Component
class TodayMinutes(private val toss: TossCandles, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 테스트가 상한을 줄인다. */
    internal var maxStocks = MAX_STOCKS

    private val lock = Any()
    private var day: LocalDate? = null
    private val bars = HashMap<String, TreeMap<LocalDateTime, MinuteCandle>>()
    /** 마지막으로 받는 데 성공한 시각 */
    private val syncedAt = HashMap<String, LocalDateTime>()
    private val readAt = HashMap<String, LocalDateTime>()

    /** 화면 요청끼리 같은 종목을 동시에 받으면 한 번만 부른다. 폴러는 태우지 않는다. */
    private val flight = SingleFlight<String, Unit>()

    /**
     * 감시 풀의 오늘 분봉을 이어 받는다. 실패한 종목 수를 돌려준다.
     *
     * 한 종목이 실패해도 나머지는 계속한다. 실패한 종목은 기존 봉을 그대로 둔다.
     * 풀 밖의 종목도 상한 안에서는 유지한다 — 다시 열면 이어 받을 수 있다.
     */
    fun sync(stockCodes: List<String>): Int {
        val keys = stockCodes.map(::baseCode)
        val today = now().toLocalDate()
        synchronized(lock) {
            rollOver(today)
            evict(keep = keys.toSet())
        }
        return keys.count { key ->
            try {
                syncOne(key, today)
                false
            } catch (e: Exception) {
                log.warn("당일 분봉 갱신 실패 stk_cd={}", key, e)
                true
            }
        }
    }

    /**
     * 마감 뒤 한 번 더 이어 받아 마지막 봉까지 확정한다. (확정된 종목별 오늘 봉, 실패 수)를 돌려준다.
     *
     * 마지막 정규 갱신은 20:00 전이라 진행 중이던 마지막 봉이 덜 찬 채 남아 있다. 이번 회차에
     * **성공한 종목만** 확정으로 본다 — 실패한 종목은 마지막 봉이 덜 찼을 수 있다.
     */
    fun settle(stockCodes: List<String>): Pair<Map<String, List<MinuteCandle>>, Int> {
        val started = now()
        val keys = stockCodes.map(::baseCode).distinct()
        val failures = sync(keys)
        return synchronized(lock) {
            if (day != started.toLocalDate()) return emptyMap<String, List<MinuteCandle>>() to failures
            keys.filter { it in bars && isSyncedSince(it, started) }.associateWith { bars.getValue(it).values.toList() } to failures
        }
    }

    /**
     * 오늘 분봉(시각 오름차순). [freshSince] 이후에 받은 값이 없으면 그 자리에서 이어 받는다.
     *
     * 받다가 실패하면 들고 있던 봉을 그대로 준다(없으면 빈 목록) — 낡은 봉이 없는 봉보다 낫다.
     */
    fun ensure(stockCode: String, freshSince: LocalDateTime): List<MinuteCandle> {
        val key = baseCode(stockCode)
        val at = now()
        synchronized(lock) {
            rollOver(at.toLocalDate())
            readAt[key] = at
            if (key in bars && isSyncedSince(key, freshSince)) return bars.getValue(key).values.toList()
            evict(keep = setOf(key))
        }
        try {
            flight.run(key) { syncIfStale(key, at.toLocalDate(), freshSince) }
        } catch (e: Exception) {
            log.error("당일 분봉 조회 실패 stk_cd={}", key, e)
        }
        return synchronized(lock) { bars[key]?.values?.toList().orEmpty() }
    }

    /** [since] 이후에 받는 데 성공했는가. 실패해 옛 봉을 돌려받았는지 가를 때 쓴다. */
    fun syncedSince(stockCode: String, since: LocalDateTime): Boolean = synchronized(lock) { isSyncedSince(baseCode(stockCode), since) }

    private fun isSyncedSince(key: String, since: LocalDateTime) = syncedAt[key]?.let { !it.isBefore(since) } ?: false

    /** 선두가 막 받고 끝난 뒤에 들어온 요청이 또 받지 않게, 받기 직전에 한 번 더 본다. */
    private fun syncIfStale(key: String, today: LocalDate, freshSince: LocalDateTime) {
        if (synchronized(lock) { isSyncedSince(key, freshSince) }) return
        syncOne(key, today)
    }

    private fun syncOne(key: String, today: LocalDate) {
        val have = synchronized(lock) { bars[key]?.lastKey() }
        val fresh = toss.fetchTodayMinuteCandles(key, have?.minus(REWRITE))
        synchronized(lock) {
            if (day != today) return // 받는 사이 날짜가 넘어갔다 — 어제 봉을 오늘 칸에 넣지 않는다
            val merged = TreeMap(bars[key].orEmpty())
            fresh.forEach { merged[it.dateTime] = it }
            bars[key] = merged
            syncedAt[key] = now()
        }
    }

    /** 상한을 넘으면 오래 안 읽힌 종목부터 버린다. 락을 쥔 채 부른다. */
    private fun evict(keep: Set<String>) {
        // 곧 들어올 종목(keep)까지 센다 — 받기 전에 자리를 비워 둬야 상한을 넘지 않는다
        val overflow = (bars.keys + keep).size - maxStocks
        if (overflow <= 0) return
        bars.keys.filter { it !in keep }.sortedBy { readAt[it] ?: LocalDateTime.MIN }.take(overflow).forEach {
            bars.remove(it)
            syncedAt.remove(it)
            readAt.remove(it)
        }
    }

    /** 날짜가 바뀌면 전부 버린다. 락을 쥔 채 부른다. */
    private fun rollOver(today: LocalDate) {
        if (day == today) return
        bars.clear()
        syncedAt.clear()
        readAt.clear()
        day = today
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    companion object {
        /** 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다. */
        private val REWRITE = Duration.ofMinutes(2)

        /** 종목 수 상한. 넘치면 가장 오래 안 읽힌 종목부터 버린다. */
        private const val MAX_STOCKS = 100
    }
}
