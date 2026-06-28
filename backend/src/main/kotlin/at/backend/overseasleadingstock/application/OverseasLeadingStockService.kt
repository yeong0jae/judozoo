package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import at.backend.platform.kis.client.KisOverseasRankingClient
import org.springframework.stereotype.Service

@Service
class OverseasLeadingStockService(
    private val rankingClient: KisOverseasRankingClient,
) {

    fun getRanking(excd: String): List<OverseasStockRankItem> =
        rankingClient.fetchTradingValueRanking(excd).map { item ->
            val negative = item.sign.trim() in setOf("4", "5") // 4:하한가 5:하락
            val sign = if (negative) -1.0 else 1.0
            OverseasStockRankItem(
                rank = item.rank.trim().toIntOrNull() ?: 0,
                exchange = item.excd.trim(),
                symbol = item.symb.trim(),
                name = item.name.trim(),
                ename = item.ename.trim(),
                price = item.last.trim().toDoubleOrNull() ?: 0.0,
                diff = sign * (item.diff.trim().toDoubleOrNull() ?: 0.0),
                rate = sign * (item.rate.trim().toDoubleOrNull() ?: 0.0),
                tradingValue = item.tamt.trim().toDoubleOrNull() ?: 0.0,
            )
        }
}
