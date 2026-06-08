package at.backend.market.application

import at.backend.leadingstock.application.filter.EtfExclusionFilter
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.time.TimeProvider
import at.backend.market.domain.regime.BasketConstituent
import at.backend.market.domain.regime.MorningBasket
import at.backend.market.domain.regime.RegimeAnchorConstituent
import at.backend.market.domain.regime.RegimeDailyRecord
import at.backend.market.domain.regime.RegimeSnapshot
import at.backend.market.infrastructure.repository.RegimeAnchorJpaRepository
import at.backend.market.infrastructure.repository.RegimeDailyRecordJpaRepository
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * 시장 레짐(오전 NXT 두 갭) 관측값 산출.
 *
 * 거래대금 상위에서 ETF/ETN을 제외해 Top N 바스켓을 구성하고, 08:15에 앵커를 고정한 뒤
 * 본장 동안 Gap2를 실시간 계산한다. 앵커는 인메모리 캐시 + DB 영속(장중 재시작 복구)으로 보관한다.
 */
@Service
class MarketRegimeService(
    private val marketClient: KiwoomMarketClient,
    private val timeProvider: TimeProvider,
    private val dailyRepository: RegimeDailyRecordJpaRepository,
    private val anchorRepository: RegimeAnchorJpaRepository,
) {
    private val etfFilter = EtfExclusionFilter()
    private val anchor = AtomicReference<DailyAnchor?>(null)
    private val latest = AtomicReference<RegimeSnapshot?>(null)

    /** 마지막으로 산출한 스냅샷 — REST 초기 응답용. 폴 전/시간 밖이면 null. */
    fun latest(): RegimeSnapshot? = latest.get()

    /** 최근 20일 결과 (최신순) — 멀티데이 비교 차트용. */
    fun recentDaily(): List<RegimeDailyRecord> = dailyRepository.findTop20ByOrderByDateDesc()

    /** 현재 바스켓으로 산출하고 최신 스냅샷 보관 + 당일 결과를 영속한다(폴러가 호출). */
    fun refresh(basket: List<LeadingStockSnapshot>): RegimeSnapshot {
        val snap = compute(basket).let { it.copy(gap1 = adjustGap1To20(it.gap1)) }
        latest.set(snap)
        recordDaily(snap)
        return snap
    }

    /** 오전 NXT(gap1)를 전일 종가(15:30) 기준 → 전일 20:00(NXT 마감) 기준으로 보정. */
    private fun adjustGap1To20(rawGap1: Double): Double {
        val prevAfter = prevAfterMarket() ?: return rawGap1
        return ((1 + rawGap1 / 100) / (1 + prevAfter / 100) - 1) * 100
    }

    /** 직전 거래일의 오후 NXT(14:00→20:00) 변동. 데이터 없으면 null. */
    private fun prevAfterMarket(): Double? {
        val prev = dailyRepository.findTopByDateBeforeOrderByDateDesc(timeProvider.today()) ?: return null
        val c1400 = prev.gap2At1400 ?: return null
        val c2000 = prev.gap2At2000 ?: return null
        return ((1 + c2000 / 100) / (1 + c1400 / 100) - 1) * 100
    }

    /** 본장(gap2 존재) 동안 당일 결과를 upsert — 종가=최신, 11:00·14:00·20:00 통과 시 고정. */
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

    /** 08:15 앵커 고정 — 당일 1회만(메모리/DB에 이미 있으면 그대로 둔다). DB에도 영속해 재시작에 대비. */
    fun captureAnchor(basket: List<LeadingStockSnapshot>) {
        if (basket.isEmpty()) return
        val today = timeProvider.today()
        if (anchor.get()?.date == today || anchorRepository.existsByDate(today)) return
        val constituents = basket.map(::toConstituent)
        anchor.set(DailyAnchor(today, MorningBasket(constituents)))
        anchorRepository.saveAll(
            constituents.map { RegimeAnchorConstituent(today, it.stockCode, it.rateAt0815, it.weight) },
        )
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

    /** 오늘 앵커 — 메모리 우선, 없으면(재시작 등) DB에서 복원해 캐시를 채운다. */
    private fun anchorForToday(): MorningBasket? {
        val today = timeProvider.today()
        anchor.get()?.takeIf { it.date == today }?.let { return it.basket }
        val rows = anchorRepository.findByDate(today)
        if (rows.isEmpty()) return null
        val basket = MorningBasket(rows.map { BasketConstituent(it.stockCode, it.rateAt0815, it.weight) })
        anchor.set(DailyAnchor(today, basket))
        return basket
    }

    private fun toConstituent(s: LeadingStockSnapshot) =
        BasketConstituent(s.stockCode, s.priceChangeRate, s.accumulatedTradingValue)

    private data class DailyAnchor(val date: LocalDate, val basket: MorningBasket)
}
