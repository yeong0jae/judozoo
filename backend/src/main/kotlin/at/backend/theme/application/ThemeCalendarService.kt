package at.backend.theme.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import at.backend.platform.kiwoom.client.KiwoomThemeClient
import at.backend.theme.domain.ThemeDailyRecord
import at.backend.theme.infrastructure.repository.ThemeDailyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class ThemeCalendarService(
    private val marketClient: KiwoomMarketClient,
    private val themeClient: KiwoomThemeClient,
    private val repository: ThemeDailyRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 거래대금 상위 종목의 거래대금을 소속 테마별로 합산해, 그날 돈이 가장 몰린 테마 상위 N개를 적재.
     * 같은 날 재실행 시 교체. 반환값=저장 건수.
     */
    @Transactional
    fun capture(): Int {
        val today = timeProvider.today()

        val byTheme = HashMap<String, Long>()
        marketClient.fetchTopTradingValueStocks(CAPTURE_STOCK_COUNT).forEach { stock ->
            if (stock.accumulatedTradingValue <= 0) return@forEach
            themeClient.fetchThemesForStock(stock.stockCode).forEach { theme ->
                byTheme.merge(theme, stock.accumulatedTradingValue, Long::plus)
            }
        }
        if (byTheme.isEmpty()) {
            log.warn { "테마 캡처 — 집계할 테마 없음, 저장 생략 (date=$today)" }
            return 0
        }

        val top = byTheme.entries.sortedByDescending { it.value }.take(CAPTURE_LIMIT)
        repository.deleteByDate(today)
        repository.flush() // 유니크(date, theme_name) 충돌 방지: 재적재 전 삭제 반영
        val saved = repository.saveAll(
            top.mapIndexed { i, e ->
                ThemeDailyRecord(date = today, rank = i + 1, themeName = e.key, tradingValue = e.value)
            },
        )
        log.info { "테마 캡처 완료 — ${saved.size}건 (date=$today)" }
        return saved.size
    }

    @Transactional(readOnly = true)
    fun getCalendar(from: LocalDate, to: LocalDate): List<ThemeDailyRecord> =
        repository.findByDateBetweenOrderByDateAscRankAsc(from, to)

    companion object {
        private const val CAPTURE_LIMIT = 10        // 하루 저장 테마 수
        private const val CAPTURE_STOCK_COUNT = 50  // 거래대금 상위 N종목을 테마로 집계
    }
}
