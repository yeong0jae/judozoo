package at.backend.platform.toss.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.roundToLong

/**
 * 토스 Market Indicators — 투자자별 매매대금(코스피/코스닥). ka 아닌 REST GET.
 * 매수/매도 거래대금(원)을 주므로 **순매수 = 매수 − 매도**로 계산해 억원(Long)으로 환산한다.
 * 기관은 7개 세부(연기금·투신·금융투자·사모·보험·은행·기타금융)를 함께 준다.
 */
@Component
class TossMarketIndicatorClient(
    private val tossRestClient: RestClient,
    private val authClient: TossAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [symbol] "KOSPI"/"KOSDAQ". [interval] 1d/1w/1mo/1y. [count] 최신순 개수. [until] 기준일(YYYY-MM-DD, inclusive).
     * 최신순으로 반환. 실패 시 빈 리스트.
     */
    fun fetchInvestorTrading(
        symbol: String,
        interval: String = "1d",
        count: Int = 10,
        until: LocalDate? = null,
    ): List<MarketInvestorRecord> {
        return try {
            val token = authClient.getAccessToken()
            val response = tossRestClient.get()
                .uri { b ->
                    b.path("/api/v1/market-indicators/{symbol}/investor-trading")
                        .queryParam("interval", interval)
                        .queryParam("count", count)
                        .also { if (until != null) it.queryParam("until", until.toString()) }
                        .build(symbol)
                }
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(InvestorTradingResponse::class.java)
                ?: return emptyList()

            response.result?.records.orEmpty().mapNotNull { it.toDomain() }
        } catch (e: Exception) {
            log.error("Toss 투자자별 매매대금 조회 실패 (symbol={}, interval={})", symbol, interval, e)
            emptyList()
        }
    }

    // ── 파싱 헬퍼 (순수 함수, 테스트 대상) ─────────────────────
    private fun Record.toDomain(): MarketInvestorRecord? {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val bd = institution?.breakdown
        return MarketInvestorRecord(
            date = d,
            sourceUpdatedAt = parseKstDateTime(updatedAt),
            individualNetEok = net(individual),
            foreignNetEok = net(foreigner),
            institutionNetEok = net(institution?.asAmount()),
            otherCorpNetEok = net(otherCorporation),
            breakdown = InstitutionBreakdown(
                pensionFundEok = net(bd?.pensionFund),
                trustEok = net(bd?.trust),
                financialInvestmentEok = net(bd?.financialInvestment),
                privateEquityEok = net(bd?.privateEquityFund),
                insuranceEok = net(bd?.insurance),
                bankEok = net(bd?.bank),
                otherFinanceEok = net(bd?.otherFinancialInstitution),
            ),
        )
    }

    companion object {
        private const val EOK = 100_000_000.0

        /** 순매수(억) = (매수 − 매도) / 1억. null/빈 값은 0. */
        fun net(a: Amount?): Long {
            if (a == null) return 0
            val buy = a.buyAmount?.trim()?.toDoubleOrNull() ?: 0.0
            val sell = a.sellAmount?.trim()?.toDoubleOrNull() ?: 0.0
            return ((buy - sell) / EOK).roundToLong()
        }

        /** "2026-06-11T18:10:00+09:00" → KST LocalDateTime. 실패 시 파싱 불가로 현재 시각 대용은 하지 않고 예외 회피. */
        fun parseKstDateTime(value: String?): LocalDateTime =
            runCatching { OffsetDateTime.parse(value).toLocalDateTime() }
                .getOrElse { LocalDateTime.MIN }
    }

    // ── 응답 DTO ──────────────────────────────────────────────
    data class InvestorTradingResponse(val result: Result? = null) {
        data class Result(val nextUntil: String? = null, val records: List<Record>? = null)
    }

    data class Record(
        val date: String? = null,
        val updatedAt: String? = null,
        val individual: Amount? = null,
        val foreigner: Amount? = null,
        val institution: Institution? = null,
        val otherCorporation: Amount? = null,
    )

    data class Institution(
        val buyAmount: String? = null,
        val sellAmount: String? = null,
        val breakdown: Breakdown? = null,
    ) {
        /** Institution도 net()에 넘길 수 있게 Amount 뷰 제공 대신, net()에서 buy/sell을 직접 읽도록 위임. */
        fun asAmount() = Amount(buyAmount, sellAmount)
    }

    data class Breakdown(
        val financialInvestment: Amount? = null,
        val insurance: Amount? = null,
        val trust: Amount? = null,
        val privateEquityFund: Amount? = null,
        val bank: Amount? = null,
        val otherFinancialInstitution: Amount? = null,
        val pensionFund: Amount? = null,
    )

    data class Amount(
        val buyAmount: String? = null,
        val sellAmount: String? = null,
    )

    // ── 도메인 반환형 ─────────────────────────────────────────
    data class MarketInvestorRecord(
        val date: LocalDate,
        val sourceUpdatedAt: LocalDateTime,
        val individualNetEok: Long,
        val foreignNetEok: Long,
        val institutionNetEok: Long,
        val otherCorpNetEok: Long,
        val breakdown: InstitutionBreakdown,
    )

    data class InstitutionBreakdown(
        val pensionFundEok: Long,
        val trustEok: Long,
        val financialInvestmentEok: Long,
        val privateEquityEok: Long,
        val insuranceEok: Long,
        val bankEok: Long,
        val otherFinanceEok: Long,
    )
}
