package at.backend.market.application

import at.backend.leadingstock.application.filter.EtfExclusionFilter
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.time.TimeProvider
import at.backend.market.domain.regime.BasketConstituent
import at.backend.market.domain.regime.MorningBasket
import at.backend.market.domain.regime.RegimeDailyRecord
import at.backend.market.domain.regime.RegimeSnapshot
import at.backend.market.infrastructure.repository.RegimeDailyRecordJpaRepository
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * 시장 레짐(아침 NXT 두 갭) 관측값 산출.
 *
 * 거래대금 상위에서 ETF/ETN을 제외해 Top N 바스켓을 구성하고, 08:15에 앵커를 고정한 뒤
 * 본장 동안 Gap2를 실시간 계산한다. 앵커는 당일 1회만 고정하며 인메모리로 보관한다.
 */
@Service
class MarketRegimeService(
    private val marketClient: KiwoomMarketClient,
    private val timeProvider: TimeProvider,
    private val dailyRepository: RegimeDailyRecordJpaRepository,
) {
    private val etfFilter = EtfExclusionFilter()
    private val anchor = AtomicReference<DailyAnchor?>(null)
    private val latest = AtomicReference<RegimeSnapshot?>(null)

    /** 마지막으로 산출한 스냅샷 — REST 초기 응답용. 폴 전/시간 밖이면 null. */
    fun latest(): RegimeSnapshot? = latest.get()

    /** 최근 20일 결과 (최신순) — 멀티데이 비교 차트용. */
    fun recentDaily(): List<RegimeDailyRecord> = dailyRepository.findTop20ByOrderByDateDesc()

    /** 현재 바스켓으로 산출하고 최신 스냅샷 보관 + 당일 결과를 영속한다(폴러가 호출). */
    fun refresh(basket: List<LeadingStockSnapshot>): RegimeSnapshot =
        compute(basket).also {
            latest.set(it)
            recordDaily(it)
        }

    /** 본장(gap2 존재) 동안 당일 결과를 upsert — 종가=최신, 10:00·15:30·20:00 통과 시 고정. */
    private fun recordDaily(snap: RegimeSnapshot) {
        val gap2 = snap.gap2 ?: return // 본장 전엔 기록하지 않음
        val now = timeProvider.now().toLocalTime()
        val date = timeProvider.today()
        val record = dailyRepository.findById(date).orElse(null)
            ?: RegimeDailyRecord(date = date, gap1 = snap.gap1, gap2Close = gap2, gap2High = gap2, gap2Low = gap2)
        record.update(snap.gap1, gap2, now)
        dailyRepository.save(record)
    }

    /** 거래대금 상위에서 ETF/ETN 제외 후 Top [size] 바스켓 후보. */
    fun fetchBasket(size: Int, fetchCount: Int): List<LeadingStockSnapshot> =
        marketClient.fetchTopTradingValueStocks(fetchCount)
            .filter { etfFilter.filter(it) }
            .take(size)

    /** 08:15 앵커 고정 — 당일 1회만(이미 오늘 앵커가 있으면 그대로 둔다). */
    fun captureAnchor(basket: List<LeadingStockSnapshot>) {
        if (basket.isEmpty()) return
        val today = timeProvider.today()
        if (anchor.get()?.date == today) return
        anchor.set(DailyAnchor(today, MorningBasket(basket.map(::toConstituent))))
    }

    /** 현재 바스켓으로 레짐 한 시점을 계산한다. */
    fun compute(basket: List<LeadingStockSnapshot>): RegimeSnapshot {
        val morning = anchorForToday()
        val currentRates = basket.associate { it.stockCode to it.priceChangeRate }
        val gap2 = morning?.gap2(currentRates)
        // gap1: 앵커가 있으면 08:15 고정값, 없으면 현재 바스켓 가중평균(프리마켓 잠정).
        val gap1 = morning?.gap1
            ?: basket.takeIf { it.isNotEmpty() }?.let { MorningBasket(it.map(::toConstituent)).gap1 }
            ?: 0.0
        return RegimeSnapshot(
            gap1 = gap1,
            gap1Locked = morning != null,
            gap2 = gap2?.value,
            gap2Coverage = gap2?.coverage,
            gap2Reliable = gap2?.reliable ?: false,
            asOf = Instant.now(),
        )
    }

    private fun anchorForToday(): MorningBasket? =
        anchor.get()?.takeIf { it.date == timeProvider.today() }?.basket

    private fun toConstituent(s: LeadingStockSnapshot) =
        BasketConstituent(s.stockCode, s.priceChangeRate, s.accumulatedTradingValue)

    private data class DailyAnchor(val date: LocalDate, val basket: MorningBasket)
}
