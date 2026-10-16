package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.stock.domain.Market
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 시장 단위 시그널 전이 한 건. `kind`로 종류를 구분한다 — 글자 컬럼이다([SignalEvent]와 같은 까닭).
 * 지수값·등락률은 **단정밀도 `float`**라 [exact]로 MySQL 글자 표현과 같은 값을 낸다.
 */
@Entity
@Table(name = "market_signal_event")
class MarketSignalEvent(
    @Column(name = "occurred_at") val occurredAt: LocalDateTime,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    val kind: String,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('BUY','SELL')")
    val side: NetTradeSide,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('FOREIGN','INSTITUTION','INDIVIDUAL')")
    val investor: InvestorType? = null,
    @Column(name = "step_level") val level: Int? = null,
    @Column(name = "net_amount_eok") val netAmountEok: Long? = null,
    @Column(name = "extreme_amount_eok") val extremeAmountEok: Long? = null,
    @Column(name = "index_value") private val indexValueFloat: Float? = null,
    @Column(name = "change_rate") private val changeRateFloat: Float? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime = occurredAt,
    @Column(name = "updated_at") val updatedAt: LocalDateTime = occurredAt,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    val indexValue: Double? get() = indexValueFloat?.let(::exact)
    val changeRate: Double? get() = changeRateFloat?.let(::exact)

    /** 단계에 해당하는 누적 순매수 임계액(억원). 단계가 없는 종류는 null. */
    val thresholdEok: Long? get() = level?.let { it * MarketSignalScale.of(market).stepEok }

    private fun exact(value: Float): Double = value.toString().toDouble()

    companion object {
        fun netBuyLevel(at: LocalDateTime, day: LocalDate, market: Market, investor: InvestorType, transition: InvestorNetBuyState.NetBuyTransition, netEok: Long, indexValue: Double, changeRate: Double) =
            MarketSignalEvent(
                at, day, MarketSignalType.NET_BUY_LEVEL.name, market, transition.side, investor, transition.level, netEok,
                indexValueFloat = indexValue.toFloat(), changeRateFloat = changeRate.toFloat(),
            )

        fun flowTurn(at: LocalDateTime, day: LocalDate, market: Market, investor: InvestorType, turn: InvestorFlowState.FlowTurn, netEok: Long, indexValue: Double, changeRate: Double) =
            MarketSignalEvent(
                at, day, MarketSignalType.NET_FLOW_TURN.name, market, turn.to, investor, netAmountEok = netEok, extremeAmountEok = turn.extremeEok,
                indexValueFloat = indexValue.toFloat(), changeRateFloat = changeRate.toFloat(),
            )

        /** 지수 반등([MarketSignalType.MA_REBOUND], 매수 쪽)·꺾임([MarketSignalType.MA_BREAKDOWN], 매도 쪽). */
        fun indexCross(kind: MarketSignalType, at: LocalDateTime, day: LocalDate, market: Market, indexValue: Double, changeRate: Double) =
            MarketSignalEvent(
                at, day, kind.name, market, if (kind == MarketSignalType.MA_REBOUND) NetTradeSide.BUY else NetTradeSide.SELL,
                indexValueFloat = indexValue.toFloat(), changeRateFloat = changeRate.toFloat(),
            )
    }
}
