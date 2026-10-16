package judozoo.market.domain

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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    val indexValue: Double get() = exact(indexValueFloat)

    val changeRate: Double get() = exact(changeRateFloat)

    fun nets() = Nets(
        individualEok, foreignEok, institutionEok, otherCorpEok,
        SessionOrg(financialInvestmentEok, insuranceEok, otherFinanceEok, trustEok, privateEquityEok, pensionFundEok, bankEok),
    )

    private fun exact(value: Float): Double = value.toString().toDouble()
}
