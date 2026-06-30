package at.backend.leadingstock.application

import at.backend.leadingstock.domain.MarketCloseSnapshot
import at.backend.leadingstock.infrastructure.repository.MarketCloseSnapshotRepository
import at.backend.platform.kiwoom.client.KiwoomSectorInvestorClient
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/** 시장 단위 장 마감 스냅샷 적재/조회. 적재는 마감 캡처가, 조회는 타임라인이 쓴다. */
@Service
class MarketCloseSnapshotService(
    private val repository: MarketCloseSnapshotRepository,
    private val sectorInvestorClient: KiwoomSectorInvestorClient,
) {

    /**
     * [date]의 코스피·코스닥 마감 투자자 순매수를 한 행씩 적재. 이미 적재된 시장은 스킵(멱등),
     * 데이터 없는 시장도 스킵. 적재한 시장 수를 돌려준다.
     */
    @Transactional
    fun capture(date: LocalDate, capturedAt: LocalDateTime): Int =
        Market.entries.count { market ->
            if (repository.existsByMarketAndTradeDate(market, date)) return@count false
            val nb = sectorInvestorClient.fetchSectorNetBuy(market.mrktTp()) ?: return@count false
            repository.save(
                MarketCloseSnapshot(
                    market = market,
                    tradeDate = date,
                    capturedAt = capturedAt,
                    foreignEok = nb.foreignEok,
                    institutionEok = nb.institutionEok,
                    individualEok = nb.individualEok,
                    indexValue = nb.indexValue,
                    changeRate = nb.changeRate,
                ),
            )
            true
        }

    @Transactional(readOnly = true)
    fun snapshotsOn(date: LocalDate): List<MarketCloseSnapshot> =
        repository.findByTradeDate(date)

    private fun Market.mrktTp() = when (this) {
        Market.KOSPI -> "0"
        Market.KOSDAQ -> "1"
    }
}
