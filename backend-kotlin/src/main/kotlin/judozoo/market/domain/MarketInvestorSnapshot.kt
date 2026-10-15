package judozoo.market.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.stock.domain.Market
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 한 시장의 장중 투자자 순매수 스냅샷(당일 누적). 단위는 억원(부호 포함).
 *
 * **적재는 주도주 시장 시그널 폴러가 한다**(공존하는 동안 Python). 여기서는 읽기만 한다 — 시황의 세션별 수급이 경계 스냅샷으로 읽는다.
 * 지수값·등락률은 **단정밀도 `float` 컬럼**이라 [exact]로 MySQL 글자 표현과 같은 값을 낸다.
 */
@Entity
@Table(name = "market_investor_snapshot")
class MarketInvestorSnapshot(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "captured_at") val capturedAt: LocalDateTime,
    @Column(name = "foreign_eok") val foreignEok: Long,
    @Column(name = "institution_eok") val institutionEok: Long,
    @Column(name = "individual_eok") val individualEok: Long,
    @Column(name = "other_corp_eok") val otherCorpEok: Long,
    // 기관 세부 — 컬럼 추가 이후 폴부터 채워진다(과거 스냅샷은 0)
    @Column(name = "financial_investment_eok") val financialInvestmentEok: Long,
    @Column(name = "trust_eok") val trustEok: Long,
    @Column(name = "pension_fund_eok") val pensionFundEok: Long,
    @Column(name = "private_equity_eok") val privateEquityEok: Long,
    @Column(name = "insurance_eok") val insuranceEok: Long,
    @Column(name = "bank_eok") val bankEok: Long,
    @Column(name = "other_finance_eok") val otherFinanceEok: Long,
    @Column(name = "index_value") private val indexValueFloat: Float,
    @Column(name = "change_rate") private val changeRateFloat: Float,
    @Column(name = "created_at") val createdAt: LocalDateTime,
    @Column(name = "updated_at") val updatedAt: LocalDateTime,
) {
    @Id
    val id: Int? = null

    /** 캡처 시점 지수 레벨. */
    val indexValue: Double get() = exact(indexValueFloat)

    val changeRate: Double get() = exact(changeRateFloat)

    fun nets() = Nets(
        individualEok, foreignEok, institutionEok, otherCorpEok,
        SessionOrg(financialInvestmentEok, insuranceEok, otherFinanceEok, trustEok, privateEquityEok, pensionFundEok, bankEok),
    )

    /** 단정밀도로 저장된 값을 MySQL이 글자로 주는 값과 같게 넓힌다 — Python 응답과 같은 수가 나온다. */
    private fun exact(value: Float): Double = value.toString().toDouble()
}
