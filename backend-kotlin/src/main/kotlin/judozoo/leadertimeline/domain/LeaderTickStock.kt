package judozoo.leadertimeline.domain

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
 * 그 분에 뽑힌 종목 한 줄. 가격·거래대금 단위는 분의 `region`으로 읽는다 — KR 원, US 달러.
 * 등락률은 **단정밀도 `float`**라 읽을 때 MySQL 글자 표현과 같은 값으로 넓힌다.
 */
@Entity
@Table(name = "leader_tick_stock")
class LeaderTickStock(
    @Column(name = "leader_tick_id") val leaderTickId: Long,
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

    val changeRate: Double get() = changeRateFloat.toString().toDouble()

    companion object {
        /** 키움 코드는 `009150_AL`처럼 거래소 접미사가 붙어 온다 — `stocks.short_code`와 이어지게 앞만 남긴다. */
        fun domestic(tickId: Long, rank: Int, s: LeadingStockSnapshot) = LeaderTickStock(
            tickId, rank, null, s.stockCode.substringBefore("_"), s.stockName, BigDecimal.valueOf(s.currentPrice),
            s.priceChangeRate.toFloat(), BigDecimal.valueOf(s.accumulatedTradingValue),
        )

        fun overseas(tickId: Long, rank: Int, s: OverseasStockRank) = LeaderTickStock(
            tickId, rank, s.exchange, s.symbol, s.name, BigDecimal.valueOf(s.price), s.rate.toFloat(),
            BigDecimal.valueOf(s.tradingValue).setScale(2, RoundingMode.HALF_EVEN),
        )
    }
}
