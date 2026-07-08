package at.backend.leadingstock.domain

import at.backend.library.jpa.BaseEntity
import at.backend.stock.domain.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 시장(코스피/코스닥) 단위 시그널 전이 한 건. [kind]로 종류를 구분한다.
 * - NET_BUY_LEVEL: 투자자 누적 순매수 단계 — [investor]/[level]/[netAmountEok] 채움.
 * [side]는 공통(순매수/순매도).
 */
@Entity
@Table(
    name = "market_signal_event",
    indexes = [
        Index(name = "idx_market_signal_event_date", columnList = "trade_date"),
    ],
)
class MarketSignalEvent(

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    // varchar로 고정 — 네이티브 ENUM이면 enum 값 추가 시 ddl-auto가 컬럼을 안 고쳐 insert가 truncate로 터진다.
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "varchar(32)")
    val kind: MarketSignalType,

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false)
    val market: Market,

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    val side: NetTradeSide,

    @Enumerated(EnumType.STRING)
    @Column(name = "investor")
    val investor: InvestorType? = null,

    @Column(name = "step_level")
    val level: Int? = null,

    @Column(name = "net_amount_eok")
    val netAmountEok: Long? = null,

    @Column(name = "extreme_amount_eok")
    val extremeAmountEok: Long? = null,

    @Column(name = "index_value")
    val indexValue: Double? = null,

    @Column(name = "change_rate")
    val changeRate: Double? = null,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    companion object {
        /** 투자자 순매수 단계 전이. [indexValue]·[changeRate]는 발생 시점 지수값/등락률. */
        fun netBuyLevel(
            occurredAt: LocalDateTime,
            tradeDate: LocalDate,
            market: Market,
            investor: InvestorType,
            side: NetTradeSide,
            level: Int,
            netAmountEok: Long,
            indexValue: Double?,
            changeRate: Double?,
        ) = MarketSignalEvent(
            occurredAt = occurredAt,
            tradeDate = tradeDate,
            kind = MarketSignalType.NET_BUY_LEVEL,
            market = market,
            side = side,
            investor = investor,
            level = level,
            netAmountEok = netAmountEok,
            indexValue = indexValue,
            changeRate = changeRate,
        )

        /**
         * 투자자 누적 순매수 흐름 전환. [side]=전환해 향하는 방향(매수 전환이면 BUY).
         * [extremeAmountEok]=되돌리기 직전 정점 누적, [netAmountEok]=전환 시점 누적(둘 다 억원·부호 포함).
         */
        fun netFlowTurn(
            occurredAt: LocalDateTime,
            tradeDate: LocalDate,
            market: Market,
            investor: InvestorType,
            side: NetTradeSide,
            extremeAmountEok: Long,
            netAmountEok: Long,
            indexValue: Double?,
            changeRate: Double?,
        ) = MarketSignalEvent(
            occurredAt = occurredAt,
            tradeDate = tradeDate,
            kind = MarketSignalType.NET_FLOW_TURN,
            market = market,
            side = side,
            investor = investor,
            netAmountEok = netAmountEok,
            extremeAmountEok = extremeAmountEok,
            indexValue = indexValue,
            changeRate = changeRate,
        )

        /** 지수 5분봉 20이평 반등(상향 돌파). 방향은 항상 매수(BUY). [indexValue]·[changeRate]는 발생 시점 값. */
        fun ma20Rebound(
            occurredAt: LocalDateTime,
            tradeDate: LocalDate,
            market: Market,
            indexValue: Double?,
            changeRate: Double?,
        ) = MarketSignalEvent(
            occurredAt = occurredAt,
            tradeDate = tradeDate,
            kind = MarketSignalType.MA20_REBOUND,
            market = market,
            side = NetTradeSide.BUY,
            indexValue = indexValue,
            changeRate = changeRate,
        )

        /** 지수 5분봉 20이평 꺾임(하향 돌파). 방향은 항상 매도(SELL). [indexValue]·[changeRate]는 발생 시점 값. */
        fun ma20Breakdown(
            occurredAt: LocalDateTime,
            tradeDate: LocalDate,
            market: Market,
            indexValue: Double?,
            changeRate: Double?,
        ) = MarketSignalEvent(
            occurredAt = occurredAt,
            tradeDate = tradeDate,
            kind = MarketSignalType.MA20_BREAKDOWN,
            market = market,
            side = NetTradeSide.SELL,
            indexValue = indexValue,
            changeRate = changeRate,
        )
    }
}
