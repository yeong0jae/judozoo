package judozoo.market.domain

import judozoo.stock.domain.Market
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalDateTime

/** 적재는 선물 투자자 폴러([judozoo.market.application.FuturesInvestorScheduler])가 1분마다 한다. */
interface FuturesInvestorSnapshotRepository : JpaRepository<FuturesInvestorSnapshot, Int> {
    fun findByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market: Market, tradeDate: LocalDate, at: LocalDateTime, limit: Limit): List<FuturesInvestorSnapshot>

    fun findByMarketAndTradeDateOrderByCapturedAtDesc(market: Market, tradeDate: LocalDate, limit: Limit): List<FuturesInvestorSnapshot>

    @Query("select max(s.tradeDate) from FuturesInvestorSnapshot s where s.market = :market and s.tradeDate <= :on")
    fun latestDateOnOrBefore(market: Market, on: LocalDate): LocalDate?

    @Query("select distinct s.tradeDate from FuturesInvestorSnapshot s where s.market = :market order by s.tradeDate desc")
    fun tradeDates(market: Market, limit: Limit): List<LocalDate>
}
