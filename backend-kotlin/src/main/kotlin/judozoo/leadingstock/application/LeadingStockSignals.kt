package judozoo.leadingstock.application

import judozoo.leadingstock.domain.IndexMinuteCandle
import judozoo.leadingstock.domain.IndexMinuteCandleRepository
import judozoo.leadingstock.domain.MarketSignalEvent
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.leadingstock.domain.SignalEvent
import judozoo.leadingstock.domain.SignalEventRepository
import judozoo.market.application.MarketInvestors
import judozoo.market.domain.MarketInvestorSnapshot
import judozoo.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** 실시간 로그 화면이 읽는 것 — 폴러가 적재한 종목·시장 시그널, 시장 수급 스냅샷, 지수 분봉. */
@Service
@Transactional(readOnly = true)
class LeadingStockSignals(
    private val signalEvents: SignalEventRepository,
    private val marketEvents: MarketSignalEventRepository,
    private val indexCandles: IndexMinuteCandleRepository,
    private val investors: MarketInvestors,
) {
    /** 그날 종목 시그널 전이, 최신순. */
    fun signalEventsOn(day: LocalDate): List<SignalEvent> = signalEvents.findByTradeDateOrderByOccurredAtDesc(day)

    /** 그날 투자자 순매수 단계·흐름 전환 + 지수 반등·꺾임, 최신순. */
    fun marketEventsOn(day: LocalDate): List<MarketSignalEvent> = marketEvents.findByTradeDateOrderByOccurredAtDesc(day)

    /**
     * 시장별 투자자 순매수 스냅샷. [at]을 주면 그날 그 시각 이하 가장 가까운 것(시그널 발생 시점 값),
     * 안 주면 [now] 이하 가장 최근 것이다 — 라이브도 키움을 부르지 않는다. 폴러가 1분마다 같은 값을 받아 적재한다.
     */
    fun investorNetBuy(at: LocalDateTime?, now: LocalDateTime): Map<Market, MarketInvestorSnapshot> = Market.entries.mapNotNull { market ->
        val snapshot = if (at != null) investors.investorSnapshotAt(market, at) else investors.latestInvestorSnapshot(market, now)
        snapshot?.let { market to it }
    }.toMap()

    /** 기준일 포함 직전 평일까지 2거래일치 지수 1분봉. 실시간 로그에서 지수 행을 고르면 우측 차트가 쓴다. */
    fun indexMinuteCandles(market: Market, day: LocalDate): List<IndexMinuteCandle> =
        indexCandles.findByMarketAndTradeDateBetweenOrderByMinuteAt(market, previousWeekday(day), day).map { it.toCandle() }

    /** 주말만 건너뛴다 — 휴장 평일이면 그날은 비어 하루치만 나온다. */
    private fun previousWeekday(on: LocalDate): LocalDate {
        var d = on.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }
}
