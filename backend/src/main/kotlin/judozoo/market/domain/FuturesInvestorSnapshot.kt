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
@Table(name = "futures_investor_snapshot")
class FuturesInvestorSnapshot(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "captured_at") val capturedAt: LocalDateTime,
    @Column(name = "foreign_qty") val foreignQty: Long,
    @Column(name = "institution_qty") val institutionQty: Long,
    @Column(name = "individual_qty") val individualQty: Long,
    @Column(name = "securities_qty") val securitiesQty: Long,
    @Column(name = "insurance_qty") val insuranceQty: Long,
    @Column(name = "merchant_bank_qty") val merchantBankQty: Long,
    @Column(name = "trust_qty") val trustQty: Long,
    @Column(name = "private_equity_qty") val privateEquityQty: Long,
    @Column(name = "fund_qty") val fundQty: Long,
    @Column(name = "bank_qty") val bankQty: Long,
    @Column(name = "other_org_qty") val otherOrgQty: Long,
    @Column(name = "other_corp_qty") val otherCorpQty: Long,
    @Column(name = "created_at") val createdAt: LocalDateTime,
    @Column(name = "updated_at") val updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    fun nets() = FuturesNets(
        foreignQty, institutionQty, individualQty, otherCorpQty,
        FuturesOrg(securitiesQty, insuranceQty, merchantBankQty, trustQty, privateEquityQty, fundQty, bankQty, otherOrgQty),
    )
}
