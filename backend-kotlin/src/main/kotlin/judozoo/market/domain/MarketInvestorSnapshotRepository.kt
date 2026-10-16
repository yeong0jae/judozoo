package judozoo.market.domain

import judozoo.stock.domain.Market
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalDateTime

/** 적재는 주도주 시장 시그널 폴러가 한다. */
interface MarketInvestorSnapshotRepository : JpaRepository<MarketInvestorSnapshot, Int> {
    /** 그날 [at] 이하 가장 가까운 스냅샷 — 세션 경계값 조회용. 최근 것부터. */
    fun findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market: Market, tradeDate: LocalDate, at: LocalDateTime, limit: Limit): List<MarketInvestorSnapshot>

    /**
     * [at] 이하 가장 최근 스냅샷 — 날짜를 가리지 않는다. 장 전·휴장일엔 직전 거래일의 마지막 스냅샷이 나온다.
     * 거래일과 시각이 함께 늘어나므로 (거래일, 시각) 역순이 곧 시각 역순이다.
     */
    fun findFirstByMarketAndCapturedAtLessThanEqualOrderByTradeDateDescCapturedAtDesc(market: Market, at: LocalDateTime): MarketInvestorSnapshot?

    /** 그날 스냅샷, 최근 것부터. */
    fun findByMarketAndTradeDateOrderByCapturedAtDesc(market: Market, tradeDate: LocalDate, limit: Limit): List<MarketInvestorSnapshot>

    /** [on] 이하에서 데이터가 실제로 있는 가장 최근 거래일. */
    @Query("select max(s.tradeDate) from MarketInvestorSnapshot s where s.market = :market and s.tradeDate <= :on")
    fun latestDateOnOrBefore(market: Market, on: LocalDate): LocalDate?

    /** 스냅샷이 있는 거래일, 최신순. */
    @Query("select distinct s.tradeDate from MarketInvestorSnapshot s where s.market = :market order by s.tradeDate desc")
    fun tradeDates(market: Market, limit: Limit): List<LocalDate>
}
