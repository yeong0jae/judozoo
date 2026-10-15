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
 * 지수선물 시장의 장중 투자자 순매수 스냅샷(당일 누적). 단위는 계약(부호 포함, 양수=순매수).
 *
 * **적재는 Python 선물 투자자 폴러(60초)만 한다**(028) — 종가베팅 등 Python 기능이 이 스냅샷을 읽는다. 여기서는 읽기만 한다.
 */
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
    // 기관 세부 — 기관계의 내역
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
    val id: Int? = null

    fun nets() = FuturesNets(
        foreignQty, institutionQty, individualQty, otherCorpQty,
        FuturesOrg(securitiesQty, insuranceQty, merchantBankQty, trustQty, privateEquityQty, fundQty, bankQty, otherOrgQty),
    )
}
