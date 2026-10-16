package judozoo.overseasleadingstock.application

import judozoo.library.EntityNotFoundException
import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasStockRank
import judozoo.overseasleadingstock.domain.OverseasStockRanks
import judozoo.overseasleadingstock.domain.UsRegularSession
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.OverseasDailyCandle
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import judozoo.platform.kis.KisOverseasProduct
import judozoo.platform.kis.KisOverseasRanking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * 해외 주도주 — 랭킹 / 상세 / 분봉 / 일봉.
 *
 * 미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위를 뽑는다. 국내와 같은 흐름 —
 * 거래대금 상위 풀에서 당일 등락률이 기준 이상인 것만 남긴다.
 */
@Service
class OverseasLeadingStockService(
    private val ranking: KisOverseasRanking,
    private val product: KisOverseasProduct,
    private val chart: KisOverseasChart,
    private val minutes: OverseasMinutes,
    private val archive: OverseasMinuteArchive,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 랭킹과 상세 평가가 공유하는 풀. 폴러가 갱신하는 캐시라 만료돼도 마지막 정상값을 준다. */
    private val pool = TtlCache<Unit, List<OverseasStockRank>>("overseasTradingValuePool", ::poolTtl, maxSize = 1, serveStale = true)

    data class StockEvaluation(val stock: OverseasStockRank, val marketCap: Long?, val filters: List<FilterResult>)

    /**
     * 장중엔 짧게, 장이 멈춘 동안(장 밖·미국 휴장)은 다음 미 동부 04:00까지 들고 있는다. 멈춘 동안엔 값이 안 바뀐다.
     * 장중 수명은 갱신 폴러(15초)가 한 번 늦어도 비지 않게 여유를 둔다.
     */
    internal fun poolTtl(): Duration {
        val (holiday, tradingHours) = calendar.usMarketStatus()
        return if (!holiday && tradingHours) POOL_TTL else calendar.untilUsSession()
    }

    /** 후보 풀을 만료 전에 새로 받아 갈아 끼운다 — 장중 갱신 폴러가 부른다. */
    fun refreshTradingValuePool() {
        pool.refresh(Unit, ::loadPool)
    }

    /** 거래대금 상위 풀에서 등락률 기준을 통과한 것만. 순위는 풀의 통합 순위를 그대로 둔다 — 다시 매기면 "몇 위"가 아니라 "몇 번째"가 된다. */
    fun candidates(minChangeRate: Double): List<OverseasStockRank> = tradingValuePool().filter { it.rate >= minChangeRate }

    /** 첫 화면용 — 거래대금·등락률이 함께 높은 상위 [count]개. 보는 사람의 등락률 기준을 받지 않는다. */
    fun leaders(count: Int): List<OverseasStockRank> = OverseasStockRanks(tradingValuePool()).leaders(count)

    /**
     * 종목 상세 — 거래대금순위·당일등락률·시가총액·시가 대비 현재가·시초가.
     *
     * 앞 둘은 후보 풀에서, 시가총액은 상품기본정보(상장주식수×현재가)로 낸다.
     * 시가는 차트가 받아 두는 1분봉 저장소에서 읽는다 — 새 요청을 늘리지 않는다.
     */
    fun evaluate(exchange: String, symbol: String): StockEvaluation {
        val stock = tradingValuePool().firstOrNull { it.exchange == exchange && it.symbol == symbol }
            ?: throw EntityNotFoundException("후보에 없는 종목: $exchange:$symbol")

        val marketCap = product.fetchMarketCap(exchange, symbol)
        val sessionOpen = sessionOpen(exchange, symbol)
        val fromOpen = sessionOpen?.let { (stock.price - it) / it * 100 }
        val prevClose = stock.previousClose
        val gap = if (sessionOpen != null && prevClose > 0) (sessionOpen - prevClose) / prevClose * 100 else null

        val filters = listOf(
            FilterResult("거래대금순위", "통합 상위 ${TOP_N}위 이내", "${stock.rank}위", stock.rank <= TOP_N, stock.rank.toDouble(), TOP_N.toDouble()),
            FilterResult(
                "당일 등락률", "${MIN_CHANGE_RATE_PCT.toInt()}% 이상", "%+.2f%%".fmt(stock.rate),
                stock.rate >= MIN_CHANGE_RATE_PCT, stock.rate, MIN_CHANGE_RATE_PCT,
            ),
            FilterResult(
                "시가총액", "$${MIN_MARKET_CAP_USD / 1_000_000_000}B 이상", marketCap?.let(::formatUsdCap) ?: "조회 불가",
                marketCap != null && marketCap >= MIN_MARKET_CAP_USD,
            ),
            FilterResult(
                "시가 대비 현재가", "현재가 ≥ 시가", fromOpen?.let { "%+.2f%%".fmt(it) } ?: "시가 없음",
                fromOpen != null && fromOpen >= 0, fromOpen, 0.0,
            ),
            FilterResult(
                "시초가", "시초가 $MAX_OPENING_GAP_PCT% 이하",
                if (gap != null) "$%,.2f (%+.2f%%)".fmt(sessionOpen, gap) else "시초가 없음",
                gap != null && gap <= MAX_OPENING_GAP_PCT, gap, MAX_OPENING_GAP_PCT,
            ),
        )
        return StockEvaluation(stock, marketCap, filters)
    }

    /** 종목 1분봉(한국 시각순 오름차순). 종목별로 들고 있다가 새 봉만 이어 받는다. */
    fun minuteCandles(exchange: String, symbol: String): List<OverseasMinuteCandle> =
        minutes.minuteCandles(exchange, symbol).sortedBy { it.dateTime }

    /** 종목 일봉(일자 오름차순). */
    fun dailyCandles(exchange: String, symbol: String): List<OverseasDailyCandle> =
        chart.fetchDailyCandles(exchange, symbol).sortedBy { it.date }

    /** 마감 뒤 들고 있는 종목의 오늘 봉을 굳혀 보관소에 넘기고, 2주 지난 날을 지운다. 실패한 종목 수를 돌려준다. */
    fun settleMinutes(): Int {
        val failures = minutes.settle()
        try {
            archive.purge(Region.US.today(clock.instant()).minus(MINUTE_ARCHIVE_RETENTION))
        } catch (e: Exception) {
            log.warn("해외 지난 날 분봉 정리 실패", e)
        }
        return failures
    }

    private fun tradingValuePool(): List<OverseasStockRank> = pool.get(Unit, ::loadPool)

    /**
     * 세 거래소를 합쳐 순위를 매기고, 상위 컷 안에서 ETF를 걷어낸다.
     *
     * **순위는 ETF를 포함한 통합 순위다.** KIS가 거래소별 순위를 주므로 합친 뒤 다시 매기는데, 국내(키움 원본 순위, ETF 포함)와
     * 같은 뜻이 되도록 ETF를 빼기 전에 매긴다.
     */
    private fun loadPool(): List<OverseasStockRank> =
        EXCHANGES.flatMap { ranking.fetchTradingValueRanking(it) }
            .map(::toRank)
            .sortedByDescending { it.tradingValue }
            .mapIndexed { i, r -> r.ranked(i + 1) }
            .take(TOP_N)
            .filterNot { it.isEtf }

    /** 최신 거래일의 정규장 시가. 분봉을 못 받으면 null — 상세의 나머지 조건은 그대로 보여 준다. */
    private fun sessionOpen(exchange: String, symbol: String): Double? {
        val bars = try {
            minutes.minuteCandles(exchange, symbol)
        } catch (e: Exception) {
            log.warn("해외 분봉을 못 받아 시가 조건을 비운다 ({}:{})", exchange, symbol, e)
            return null
        }
        if (bars.isEmpty()) return null
        val day = bars.maxOf { it.tradingDay }
        return UsRegularSession.openPrice(
            bars.filter { it.tradingDay == day }.map { it.dateTime.atZone(KST).withZoneSameInstant(Region.US.zone).toLocalDateTime() to it.open },
        )
    }

    companion object {
        val EXCHANGES = listOf("NAS", "NYS", "AMS")
        const val TOP_N = 40

        /** 상세: 당일 등락률 하한 */
        const val MIN_CHANGE_RATE_PCT = 5.0

        /** 상세: 시가총액 $2B 하한 */
        const val MIN_MARKET_CAP_USD = 2_000_000_000L

        /** 상세: 전일 종가 대비 시가 상한 — 국내와 같다 */
        const val MAX_OPENING_GAP_PCT = 7.0

        private val POOL_TTL = Duration.ofSeconds(20)

        /** 지난 날 분봉 보관 기간 — 수정주가(액면분할 등) 소급이 반영되지 않는 봉을 오래 두지 않는다. */
        private val MINUTE_ARCHIVE_RETENTION = java.time.Period.ofDays(14)

        /** 숫자 표기는 로캘을 타지 않는다 — 소수점·자릿수 구분이 서버 로캘에 따라 바뀌면 안 된다. */
        private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

        /** 1조 이상은 $X.XXT, 그 외는 $X,XXXB. */
        fun formatUsdCap(usd: Long): String =
            if (usd >= 1_000_000_000_000) "$%.2fT".fmt(usd / 1_000_000_000_000.0) else "$%,dB".fmt(usd / 1_000_000_000)

        /** rate(등락율)는 이미 부호 포함. diff(대비)는 크기라 sign으로 방향을 부여한다(4:하한가 5:하락). */
        private fun toRank(item: KisOverseasRanking.OverseasRankItem): OverseasStockRank {
            val sign = if (item.sign.trim() in setOf("4", "5")) -1.0 else 1.0
            fun number(value: String) = value.trim().toDoubleOrNull() ?: 0.0
            return OverseasStockRank(
                rank = 0, // 통합 정렬 후 재부여
                exchange = item.excd.trim(), symbol = item.symb.trim(), name = item.name.trim(), ename = item.ename.trim(),
                price = number(item.last), diff = sign * number(item.diff), rate = number(item.rate), tradingValue = number(item.tamt),
            )
        }
    }
}
