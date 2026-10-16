package judozoo.market.infrastructure

import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.platform.yahoo.YahooClient.YahooBar
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@IntegrationTest
class MinuteArchivesTest @Autowired constructor(
    private val index: IndexMinuteArchive,
    private val futures: FuturesMinuteArchive,
    private val yahoo: YahooMinuteArchive,
    private val jdbc: JdbcTemplate,
) {
    private val day = LocalDate.of(2026, 9, 28)

    @BeforeEach
    fun 빈_테이블() {
        listOf("index_minute_day", "futures_minute_day", "yahoo_minute_day").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Nested
    inner class Python이_쓴_행 {
        @Test
        fun `지수 분봉을 읽는다`() {
            jdbc.update(
                "INSERT INTO index_minute_day VALUES ('KOSPI', ?, ?)", day,
                """[{"timestamp": "2026-09-28T09:00:00", "open": 2600.5, "high": 2601.0, "low": 2599.0, "close": 2600.0, "volume": 1200.0}]""",
            )

            index.get(Market.KOSPI, day) shouldBe listOf(TossCandle(LocalDateTime.of(2026, 9, 28, 9, 0), 2600.5, 2601.0, 2599.0, 2600.0, 1200.0))
        }

        @Test
        fun `선물 분봉을 읽는다`() {
            jdbc.update(
                "INSERT INTO futures_minute_day VALUES ('KOSDAQ', 'CONTRACT', 1, ?, ?)", day,
                """[{"date": "2026-09-28", "time": "18:00:00", "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 10.0}]""",
            )

            futures.get(Market.KOSDAQ, "CONTRACT", true, day) shouldBe listOf(FuturesBar("2026-09-28", "18:00:00", 1.0, 2.0, 0.5, 1.5, 10.0))
        }

        @Test
        fun `야후 분봉을 읽는다`() {
            jdbc.update(
                "INSERT INTO yahoo_minute_day VALUES ('^IXIC', ?, ?)", day,
                """[{"date": "2026-09-28", "time": "22:30:00", "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 10.0}]""",
            )

            yahoo.recent("^IXIC", day) shouldBe mapOf(day to listOf(YahooBar("2026-09-28", "22:30:00", 1.0, 2.0, 0.5, 1.5, 10.0)))
        }
    }

    @Nested
    inner class Kotlin이_쓴_행 {
        @Test
        fun `지수 분봉의 시각을 초까지 적는다`() {
            index.put(Market.KOSPI, day, listOf(TossCandle(LocalDateTime.of(2026, 9, 28, 9, 0), 1.0, 1.0, 1.0, 1.0, 1.0)))

            jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(candles, '$[0].timestamp')) FROM index_minute_day", String::class.java) shouldBe
                "2026-09-28T09:00:00"
        }

        @Test
        fun `같은 날을 다시 넣으면 갈아 끼운다`() {
            val bar = FuturesBar("2026-09-28", "09:00:00", 1.0, 2.0, 0.5, 1.5, 10.0)
            futures.put(Market.KOSPI, "CONTRACT", false, day, listOf(bar))
            futures.put(Market.KOSPI, "CONTRACT", false, day, listOf(bar.copy(close = 2.0)))

            futures.get(Market.KOSPI, "CONTRACT", false, day)!!.single().close shouldBe 2.0
        }
    }
}
