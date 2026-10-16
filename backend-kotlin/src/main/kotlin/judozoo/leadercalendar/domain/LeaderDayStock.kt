package judozoo.leadercalendar.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.overseasleadingstock.domain.OverseasStockRank
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 그날 뽑힌 종목 한 줄. 가격·거래대금 단위는 날의 `region`으로 읽는다 — KR 원, US 달러.
 * 이름은 그날 것을 박제한다 — 상호가 바뀌어도 그날 화면에 뜬 이름이 남는다.
 */
@Entity
@Table(name = "leader_day_stock")
class LeaderDayStock(
    @Column(name = "leader_day_id") val leaderDayId: Long,
    // MySQL 8 예약어라 따옴표로 감싼다
    @Column(name = "`rank`", columnDefinition = "smallint") val rank: Int,
    /** US만 — NAS / NYS / AMS */
    val exchange: String?,
    /** KR 6자리 단축코드 / US 심볼 */
    val code: String,
    val name: String,
    val price: BigDecimal,
    @Column(name = "change_rate") private val changeRateFloat: Float,
    @Column(name = "trading_value") val tradingValue: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 단정밀도로 저장된 값을 MySQL이 글자로 주는 값과 같게 넓힌다. */
    val changeRate: Double get() = changeRateFloat.toString().toDouble()

    companion object {
        /** 키움 코드는 `009150_AL`처럼 거래소 접미사가 붙어 온다 — `stocks.short_code`와 이어지게 앞만 남긴다. */
        fun domestic(dayId: Long, rank: Int, s: LeadingStockSnapshot) = LeaderDayStock(
            dayId, rank, null, s.stockCode.substringBefore("_"), s.stockName, BigDecimal.valueOf(s.currentPrice),
            s.priceChangeRate.toFloat(), BigDecimal.valueOf(s.accumulatedTradingValue),
        )

        fun overseas(dayId: Long, rank: Int, s: OverseasStockRank) = LeaderDayStock(
            dayId, rank, s.exchange, s.symbol, s.name, BigDecimal.valueOf(s.price), s.rate.toFloat(),
            BigDecimal.valueOf(s.tradingValue).setScale(2, RoundingMode.HALF_EVEN),
        )
    }
}
