package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import at.backend.platform.kis.client.KisOverseasRankingClient
import org.springframework.stereotype.Service

@Service
class OverseasLeadingStockService(
    private val rankingClient: KisOverseasRankingClient,
) {

    /**
     * 미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위 40위.
     * 거래소별 30위 풀(최대 90종목)을 거래대금 내림차순으로 섞어 통합 순위를 재부여한다.
     */
    fun getRanking(): List<OverseasStockRankItem> =
        EXCHANGES.flatMap { excd -> rankingClient.fetchTradingValueRanking(excd).map { it.toRankItem() } }
            .sortedByDescending { it.tradingValue }
            .take(TOP_N)
            .mapIndexed { i, item -> item.copy(rank = i + 1) }

    private fun KisOverseasRankingClient.OverseasRankItem.toRankItem(): OverseasStockRankItem {
        val negative = sign.trim() in setOf("4", "5") // 4:하한가 5:하락
        val signMul = if (negative) -1.0 else 1.0
        return OverseasStockRankItem(
            rank = 0, // 통합 정렬 후 재부여
            exchange = excd.trim(),
            symbol = symb.trim(),
            name = name.trim(),
            ename = ename.trim(),
            price = last.trim().toDoubleOrNull() ?: 0.0,
            diff = signMul * (diff.trim().toDoubleOrNull() ?: 0.0),
            rate = signMul * (rate.trim().toDoubleOrNull() ?: 0.0),
            tradingValue = tamt.trim().toDoubleOrNull() ?: 0.0,
        )
    }

    companion object {
        private val EXCHANGES = listOf("NAS", "NYS", "AMS")
        private const val TOP_N = 40
    }
}
