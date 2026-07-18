package at.backend.theme.application

import at.backend.library.time.TimeProvider
import at.backend.market.application.HolidayService
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import at.backend.platform.kiwoom.client.KiwoomThemeClient
import at.backend.theme.domain.ThemeDailyRecord
import at.backend.theme.domain.ThemeDailyStock
import at.backend.theme.infrastructure.repository.ThemeDailyRepository
import at.backend.theme.infrastructure.repository.ThemeDailyStockRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Service
class ThemeCalendarService(
    private val marketClient: KiwoomMarketClient,
    private val themeClient: KiwoomThemeClient,
    private val repository: ThemeDailyRepository,
    private val stockRepository: ThemeDailyStockRepository,
    private val holidayService: HolidayService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 거래대금 상위 종목의 거래대금을 소속 테마별로 합산해, 그날 돈이 가장 몰린 테마 상위 N개와
     * 각 테마에 기여한 종목을 적재. 같은 날 재실행 시 교체. 반환값=저장한 테마 수.
     */
    @Transactional
    fun capture(): Int {
        // 당일 장 데이터가 존재하는 가장 최근 거래일에 적재. 프리마켓 시작(08:00) 전 새벽에 캡처하면
        // 아직 당일 데이터가 없어 직전 거래일(예: 월요일 새벽→금요일)로 저장한다 —
        // 거래대금(전일 종가 기준)이 당일 날짜로 잘못 들어가는 걸 막는다.
        val captureDate = mostRecentTradingDayWithData(timeProvider.now())

        // 테마명 → 기여 종목들 (상위 거래대금 종목 중 그 테마 소속)
        val byTheme = LinkedHashMap<String, MutableList<Contributor>>()
        marketClient.fetchTopTradingValueStocks(CAPTURE_STOCK_COUNT).forEach { stock ->
            if (stock.accumulatedTradingValue <= 0) return@forEach
            themeClient.fetchThemesForStock(stock.stockCode).forEach { theme ->
                byTheme.getOrPut(theme) { mutableListOf() }.add(
                    Contributor(
                        stock.stockCode,
                        stock.stockName,
                        stock.accumulatedTradingValue,
                        stock.priceChangeRate,
                    ),
                )
            }
        }
        if (byTheme.isEmpty()) {
            log.warn { "테마 캡처 — 집계할 테마 없음, 저장 생략 (date=$captureDate)" }
            return 0
        }

        val ranked = byTheme.entries
            .map { it.key to it.value }
            .sortedByDescending { (_, contribs) -> contribs.sumOf { it.tradingValue } }
            .take(CAPTURE_LIMIT)

        stockRepository.deleteByDate(captureDate)
        repository.deleteByDate(captureDate)
        repository.flush() // 유니크(date, theme_name) 충돌 방지: 재적재 전 삭제 반영

        val savedParents = repository.saveAll(
            ranked.mapIndexed { i, (name, contribs) ->
                ThemeDailyRecord(
                    date = captureDate,
                    rank = i + 1,
                    themeName = name,
                    tradingValue = contribs.sumOf { it.tradingValue },
                )
            },
        )
        val children = savedParents.flatMapIndexed { i, parent ->
            ranked[i].second.sortedByDescending { it.tradingValue }.map { c ->
                ThemeDailyStock(
                    themeDailyId = parent.id,
                    date = captureDate,
                    stockCode = c.code,
                    stockName = c.name,
                    tradingValue = c.tradingValue,
                    priceChangeRate = c.priceChangeRate,
                )
            }
        }
        stockRepository.saveAll(children)

        log.info { "테마 캡처 완료 — 테마 ${savedParents.size}건, 종목 ${children.size}건 (date=$captureDate)" }
        return savedParents.size
    }

    @Transactional(readOnly = true)
    fun getCalendar(from: LocalDate, to: LocalDate): List<ThemeWithStocks> {
        // 휴장일에 잘못 캡처된 레코드는 숨긴다(폴러가 공휴일 인식 전 쌓았을 수 있음). 판정 불가한 과거는 그대로.
        val records = repository.findByDateBetweenOrderByDateAscRankAsc(from, to)
            .filter { holidayService.isOpen(it.date) != false }
        if (records.isEmpty()) return emptyList()
        val stocksByParent = stockRepository
            .findByThemeDailyIdInOrderByTradingValueDesc(records.map { it.id })
            .groupBy { it.themeDailyId }
        return records.map { ThemeWithStocks(it, stocksByParent[it.id].orEmpty()) }
    }

    private data class Contributor(
        val code: String,
        val name: String,
        val tradingValue: Long,
        val priceChangeRate: Double,
    )

    data class ThemeWithStocks(
        val record: ThemeDailyRecord,
        val stocks: List<ThemeDailyStock>,
    )

    /**
     * 당일 장 데이터가 존재하는 가장 최근 거래일.
     * 평일이고 프리마켓 시작(08:00) 이후면 당일, 그 외(평일 개장 전·주말)는 직전 평일로 거슬러 올라간다.
     */
    private fun mostRecentTradingDayWithData(now: LocalDateTime): LocalDate {
        var d = now.toLocalDate()
        val isWeekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        val todayHasData = !isWeekend && now.toLocalTime() >= SESSION_START
        if (!todayHasData) {
            do {
                d = d.minusDays(1)
            } while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
        }
        return d
    }

    companion object {
        private const val CAPTURE_LIMIT = 12        // 하루 저장 테마 수
        private const val CAPTURE_STOCK_COUNT = 40  // 거래대금 상위 N종목을 테마로 집계
        private val SESSION_START = LocalTime.of(8, 0) // 프리마켓 개장 — 이 시각 후 당일 데이터 존재
    }
}
