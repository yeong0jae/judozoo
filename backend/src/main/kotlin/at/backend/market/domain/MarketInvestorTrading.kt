package at.backend.market.domain

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
 * 토스 투자자별 매매대금 장중 스냅샷 — 폴러가 당일 누적 순매수(억원)를 주기적으로 한 줄씩 적재한다.
 * 세션(오전/오후/막판) 수급은 이 스냅샷들을 경계 시각에 뽑아 차이(diff)로 계산한다.
 * [sourceUpdatedAt]는 토스가 알려주는 데이터 갱신 시각 — 장중 실시간 갱신 여부 판정에 쓴다.
 * 순매수는 개인·외국인·기관·기타법인 + 기관 7세부, 단위는 억원(부호 포함).
 */
@Entity
@Table(
    name = "market_investor_trading",
    indexes = [
        Index(name = "idx_market_investor_trading_lookup", columnList = "market, trade_date, captured_at"),
    ],
)
class MarketInvestorTrading(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val market: Market,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "captured_at", nullable = false)
    val capturedAt: LocalDateTime,

    @Column(name = "source_updated_at", nullable = false)
    val sourceUpdatedAt: LocalDateTime,

    @Column(name = "individual_eok", nullable = false)
    val individualEok: Long,

    @Column(name = "foreign_eok", nullable = false)
    val foreignEok: Long,

    @Column(name = "institution_eok", nullable = false)
    val institutionEok: Long,

    @Column(name = "other_corp_eok", nullable = false)
    val otherCorpEok: Long,

    @Column(name = "pension_fund_eok", nullable = false)
    val pensionFundEok: Long,

    @Column(name = "trust_eok", nullable = false)
    val trustEok: Long,

    @Column(name = "financial_investment_eok", nullable = false)
    val financialInvestmentEok: Long,

    @Column(name = "private_equity_eok", nullable = false)
    val privateEquityEok: Long,

    @Column(name = "insurance_eok", nullable = false)
    val insuranceEok: Long,

    @Column(name = "bank_eok", nullable = false)
    val bankEok: Long,

    @Column(name = "other_finance_eok", nullable = false)
    val otherFinanceEok: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
