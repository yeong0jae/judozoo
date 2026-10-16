package judozoo.leadingstock.application

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

/** 실시간 로그가 읽는 표 — 폴러가 쌓은 것을 그날 것만, 최신순으로. */
@IntegrationTest
class LeadingStockSignalsTest @Autowired constructor(private val signals: LeadingStockSignals, private val jdbc: JdbcTemplate) {
    private val today = LocalDate.of(2026, 9, 11)
    private val at = today.atTime(10, 0)

    @BeforeEach
    fun 빈_테이블() {
        listOf("signal_event", "market_signal_event", "market_investor_snapshot", "index_minute_candle").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun signal(name: String, occurredAt: LocalDateTime, day: LocalDate = today) = jdbc.update(
        """INSERT INTO signal_event (occurred_at, trade_date, stock_code, stock_name, event_type, current_price, price_change_rate,
           trading_value, spike_ratio, spike_direction, created_at, updated_at) VALUES (?, ?, '005930', ?, 'VOLUME_SPIKE', 1000, 5.12, 100, 3.3, 'BUY', ?, ?)""",
        occurredAt, day, name, at, at,
    )

    private fun snapshot(market: Market, capturedAt: LocalDateTime, foreign: Long) = jdbc.update(
        """INSERT INTO market_investor_snapshot (market, trade_date, captured_at, foreign_eok, institution_eok, individual_eok, other_corp_eok,
           financial_investment_eok, trust_eok, pension_fund_eok, private_equity_eok, insurance_eok, bank_eok, other_finance_eok,
           index_value, change_rate, created_at, updated_at) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2600.5, 1.2, ?, ?)""",
        market.name, capturedAt.toLocalDate(), capturedAt, foreign, capturedAt, capturedAt,
    )

    @Nested
    inner class 시그널_로그 {
        @Test
        fun `그날 전이를 최신순으로 주고 단정밀도 소수는 Python과 같은 값이다`() {
            listOf(0, 5, 10).forEach { signal("종목$it", at.plusMinutes(it.toLong())) }
            signal("어제것", at.minusDays(1), today.minusDays(1))

            val rows = signals.signalEventsOn(today)

            rows.map { it.stockName } shouldBe listOf("종목10", "종목5", "종목0")
            (rows[0].priceChangeRate to rows[0].spikeRatio) shouldBe (5.12 to 3.3)
        }

        @Test
        fun `시장 시그널은 단계에 해당하는 임계액을 함께 준다`() {
            jdbc.update(
                """INSERT INTO market_signal_event (occurred_at, trade_date, kind, market, side, investor, step_level, net_amount_eok, index_value, change_rate, created_at, updated_at)
                   VALUES (?, ?, 'NET_BUY_LEVEL', 'KOSPI', 'BUY', 'FOREIGN', 3, 32000, 2653.81, 1.2, ?, ?)""",
                at, today, at, at,
            )
            jdbc.update(
                "INSERT INTO market_signal_event (occurred_at, trade_date, kind, market, side, created_at, updated_at) VALUES (?, ?, 'MA_REBOUND', 'KOSDAQ', 'BUY', ?, ?)",
                at.plusMinutes(1), today, at, at,
            )

            val (rebound, level) = signals.marketEventsOn(today)

            (level.thresholdEok to level.indexValue) shouldBe (30_000L to 2653.81) // 코스피는 1조 단위 — 3단계면 3조
            (rebound.thresholdEok to rebound.level).shouldBe(null to null)
            rebound.investor.shouldBeNull()
        }
    }

    @Nested
    inner class 시장_수급 {
        @Test
        fun `지금 이하 가장 최근 스냅샷을 준다`() {
            snapshot(Market.KOSPI, today.atTime(9, 58), 100)
            snapshot(Market.KOSPI, today.atTime(9, 59), 200)
            snapshot(Market.KOSPI, today.atTime(10, 1), 999)
            snapshot(Market.KOSDAQ, today.atTime(9, 59), -50)

            signals.investorNetBuy(null, at).mapValues { it.value.foreignEok } shouldBe mapOf(Market.KOSPI to 200L, Market.KOSDAQ to -50L)
        }

        @Test
        fun `장 전에는 직전 거래일 마지막 스냅샷을 준다`() {
            snapshot(Market.KOSPI, today.atTime(19, 59), 300)
            snapshot(Market.KOSPI, today.atTime(20, 0), 400)

            signals.investorNetBuy(null, LocalDateTime.of(2026, 9, 14, 7, 0)).mapValues { it.value.foreignEok } shouldBe mapOf(Market.KOSPI to 400L)
        }

        /** 시그널이 난 그 시각의 값 — 다른 날로 넘어가지 않는다. */
        @Test
        fun `시각을 주면 그날 그 시각 이하 가장 가까운 스냅샷이다`() {
            snapshot(Market.KOSPI, today.minusDays(1).atTime(15, 0), 1)
            snapshot(Market.KOSPI, today.atTime(9, 59), 200)
            snapshot(Market.KOSPI, today.atTime(10, 1), 999)

            signals.investorNetBuy(at, at).mapValues { it.value.foreignEok } shouldBe mapOf(Market.KOSPI to 200L)
            signals.investorNetBuy(today.atTime(9, 0), at) shouldBe emptyMap()
        }
    }

    @Test
    fun `지수 분봉은 기준일과 직전 평일 두 날치다`() {
        listOf(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14)).forEach { day ->
            jdbc.update(
                """INSERT INTO index_minute_candle (market, trade_date, minute_at, open_price, high_price, low_price, close_price, volume, created_at, updated_at)
                   VALUES ('KOSPI', ?, ?, 2653.81, 2660, 2650, 2655.5, 10, ?, ?)""",
                day, day.atTime(9, 0), at, at,
            )
        }

        val bars = signals.indexMinuteCandles(Market.KOSPI, LocalDate.of(2026, 9, 14)) // 월요일 — 직전 평일은 금요일

        bars.map { it.minute.toLocalDate() } shouldBe listOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14))
        bars[0].open shouldBe 2653.81
    }
}
