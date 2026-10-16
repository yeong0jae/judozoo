package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 판의 종목 한 줄 — 20:00 스냅샷. 순매수는 키움 단위 그대로 **백만원**이다(억원으로 바꾸는 건 화면 쪽 일).
 * 소수 열은 **단정밀도 `float`**라 읽을 때 MySQL 글자 표현과 같은 값으로 넓힌다. 가격 열은 `int`다 — 코드에서는 Long으로 다룬다.
 */
@Entity
@Table(name = "bet_round_stock")
class BetRoundStock(
    @Column(name = "trading_day") val tradingDay: LocalDate,
    @Column(name = "stock_code") val stockCode: String,
    @Column(name = "stock_name") val stockName: String,
    /** KOSPI / KOSDAQ */
    val market: String,
    // MySQL 8 예약어(LEAD 창 함수)라 따옴표로 감싼다
    @Column(name = "`lead`") val lead: Boolean,
    val nxt: Boolean,
    /** 20:00 종가 = 매수가 */
    @Column(name = "close_price", columnDefinition = "int") val closePrice: Long,
    @Column(name = "high_price", columnDefinition = "int") val highPrice: Long,
    @Column(name = "low_price", columnDefinition = "int") val lowPrice: Long,
    @Column(name = "change_rate") private val changeRateFloat: Float,
    @Column(name = "foreign_net") val foreignNet: Long,
    @Column(name = "institution_net") val institutionNet: Long,
    @Column(name = "foreign_5d") val foreign5d: Long,
    @Column(name = "institution_5d") val institution5d: Long,
    /** 최근 고점과의 거리(%) — 직전 60거래일 최고가 대비. 일봉이 없으면 null */
    @Column(name = "recent_high_gap") private val recentHighGapFloat: Float?,
    /** 15:30 정규장 종가 — 애프터 버팀 판정. 못 잡았으면 null */
    @Column(name = "regular_close", columnDefinition = "int") val regularClose: Long?,
    /** {near_high, foreign, institution, market_late, recent_high, after_hold} */
    @Column(name = "checks", columnDefinition = "json") val checksJson: String,
    val grade: String,
    val crowd: Int,
    @Column(name = "pot_man") val potMan: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "sell_price", columnDefinition = "decimal(12,1)")
    var sellPrice: Double? = null
        protected set

    /** 실제로 값을 낸 5분의 시작. 창에 체결이 없으면 미뤄진 시각, 끝까지 없으면 null */
    @Column(name = "sell_window_start")
    var sellWindowStart: LocalDateTime? = null
        protected set

    @Column(name = "rate")
    private var rateFloat: Float? = null

    val changeRate: Double get() = exact(changeRateFloat)
    val recentHighGap: Double? get() = recentHighGapFloat?.let(::exact)
    val rate: Double? get() = rateFloat?.let(::exact)
    val sold: Boolean get() = rateFloat != null

    fun sell(price: SellWindow.SellPrice) {
        sellPrice = price.price
        sellWindowStart = price.windowStart
        rateFloat = Outcome.of(1, closePrice, price.price).rate.toFloat()
    }

    private fun exact(value: Float): Double = value.toString().toDouble()
}
