package at.backend.leadingstock.application

import at.backend.platform.kiwoom.client.KiwoomInvestorClient
import org.springframework.stereotype.Service

/**
 * 종목별 일자별 외국인·기관 순매수 추이.
 * SOR 통합 + NXT 단독 두 번 호출해 일자 단위로 합쳐서 반환.
 * 키움 컨벤션: stk_cd + "_NX" → NXT 단독, 기본 코드는 SOR 통합.
 */
@Service
class InvestorTrendService(
    private val investorClient: KiwoomInvestorClient,
) {

    fun getTrend(stockCode: String): List<InvestorTrendDayDto> {
        val combined = investorClient.fetchInvestorTrend(stockCode)
        val nxt = investorClient.fetchInvestorTrend("${stockCode}_NX")
        val nxtByDate = nxt.associateBy { it.date }

        return combined.map { day ->
            val n = nxtByDate[day.date]
            InvestorTrendDayDto(
                date = day.date,
                individualNet = day.individualNet,
                foreignNet = day.foreignNet,
                institutionNet = day.institutionNet,
                individualNetNxt = n?.individualNet ?: 0,
                foreignNetNxt = n?.foreignNet ?: 0,
                institutionNetNxt = n?.institutionNet ?: 0,
            )
        }
    }

    /** SOR 통합과 NXT 단독을 한 일자에 합친 표시용. 단위 백만원. */
    data class InvestorTrendDayDto(
        val date: String,
        val individualNet: Long,
        val foreignNet: Long,
        val institutionNet: Long,
        val individualNetNxt: Long,
        val foreignNetNxt: Long,
        val institutionNetNxt: Long,
    )
}
