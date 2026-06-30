package at.backend.leadingstock.application

import at.backend.leadingstock.domain.InvestorFlowState
import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketFlowStateSnapshot
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.MarketSignalType
import at.backend.leadingstock.domain.NetTradeSide
import at.backend.leadingstock.infrastructure.repository.MarketFlowStateSnapshotRepository
import at.backend.leadingstock.infrastructure.repository.MarketSignalEventRepository
import at.backend.platform.kiwoom.client.KiwoomSectorInvestorClient
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 시장 단위 시그널 전이 적재/조회. 적재는 폴러가, 조회는 실시간 로그가 쓴다. */
@Service
class MarketSignalEventService(
    private val repository: MarketSignalEventRepository,
    private val sectorInvestorClient: KiwoomSectorInvestorClient,
    private val flowStateRepository: MarketFlowStateSnapshotRepository,
) {

    /** 그날 흐름 전환 상태 스냅샷 복원 — 키 "market|investor". 재시작으로 메모리가 비었을 때 정점을 되살린다. */
    @Transactional(readOnly = true)
    fun loadFlowStates(date: LocalDate): Map<String, InvestorFlowState> =
        flowStateRepository.findByTradeDate(date).associate {
            "${it.market}|${it.investor}" to InvestorFlowState.restore(it.side, it.extremeEok)
        }

    /** 흐름 전환 상태를 스냅샷으로 upsert. 아직 방향이 없으면(추적 전) 저장하지 않는다. */
    @Transactional
    fun saveFlowState(date: LocalDate, market: Market, investor: InvestorType, state: InvestorFlowState) {
        val side = state.currentSide ?: return
        val existing = flowStateRepository.findByMarketAndInvestor(market, investor)
        if (existing != null) {
            existing.side = side
            existing.extremeEok = state.currentExtremeEok
            existing.tradeDate = date
        } else {
            flowStateRepository.save(
                MarketFlowStateSnapshot(market, investor, side, state.currentExtremeEok, date),
            )
        }
    }

    /** 코스피·코스닥 각 시장의 당일 누적 투자자(외인·기관·개인) 순매수. 데이터 없는 시장은 제외. */
    fun investorNetBuy(): Map<Market, KiwoomSectorInvestorClient.SectorInvestorNetBuy> =
        Market.entries
            .mapNotNull { market -> sectorInvestorClient.fetchSectorNetBuy(market.mrktTp())?.let { market to it } }
            .toMap()

    private fun Market.mrktTp() = when (this) {
        Market.KOSPI -> "0"
        Market.KOSDAQ -> "1"
    }

    @Transactional
    fun recordAll(events: List<MarketSignalEvent>) {
        if (events.isNotEmpty()) repository.saveAll(events)
    }

    @Transactional(readOnly = true)
    fun eventsOn(date: LocalDate): List<MarketSignalEvent> =
        repository.findByTradeDateOrderByOccurredAtDesc(date)

    /** 그날 한 시장의 최신 캔들 연속 시그널 — 폴러 디바운스 상태 복원용. */
    @Transactional(readOnly = true)
    fun latestCandleStreak(market: Market, date: LocalDate): MarketSignalEvent? =
        repository.findFirstByMarketAndKindAndTradeDateOrderByOccurredAtDesc(
            market, MarketSignalType.CANDLE_STREAK, date,
        )

    /** 그날 같은 (시장·투자자·방향·단계) 순매수 시그널이 이미 적재됐는지. */
    @Transactional(readOnly = true)
    fun alreadyFiredNetBuyLevel(
        date: LocalDate,
        market: Market,
        investor: InvestorType,
        side: NetTradeSide,
        level: Int,
    ): Boolean =
        repository.existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(
            date, MarketSignalType.NET_BUY_LEVEL, market, investor, side, level,
        )
}
