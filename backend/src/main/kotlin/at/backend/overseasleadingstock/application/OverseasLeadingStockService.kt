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
     * 거래소별 30위 풀(최대 90종목)에서 ETF를 거른 뒤 거래대금 내림차순으로 섞어 통합 순위를 재부여한다.
     */
    fun getRanking(): List<OverseasStockRankItem> =
        EXCHANGES.flatMap { excd -> rankingClient.fetchTradingValueRanking(excd).map { it.toRankItem() } }
            .filterNot { it.isEtf() }
            .sortedByDescending { it.tradingValue }
            .take(TOP_N)
            .mapIndexed { i, item -> item.copy(rank = i + 1) }

    /**
     * 거래대금순위 API엔 ETF 구분 필드가 없어 영문명 키워드로 판별(휴리스틱).
     * 발행사 브랜드 위주로 잡아 일반기업 오탐을 줄인다 — TRUST·FUND 등 흔한 단어는 일부러 제외.
     */
    private fun OverseasStockRankItem.isEtf(): Boolean {
        val upper = ename.uppercase()
        return ETF_KEYWORDS.any { upper.contains(it) }
    }

    private fun KisOverseasRankingClient.OverseasRankItem.toRankItem(): OverseasStockRankItem {
        // rate(등락율)는 이미 부호 포함("-6.69"). diff(대비)는 절댓값이라 sign으로 방향 부여.
        val negative = sign.trim() in setOf("4", "5") // 4:하한가 5:하락
        val diffSign = if (negative) -1.0 else 1.0
        return OverseasStockRankItem(
            rank = 0, // 통합 정렬 후 재부여
            exchange = excd.trim(),
            symbol = symb.trim(),
            name = name.trim(),
            ename = ename.trim(),
            price = last.trim().toDoubleOrNull() ?: 0.0,
            diff = diffSign * (diff.trim().toDoubleOrNull() ?: 0.0),
            rate = rate.trim().toDoubleOrNull() ?: 0.0,
            tradingValue = tamt.trim().toDoubleOrNull() ?: 0.0,
        )
    }

    companion object {
        private val EXCHANGES = listOf("NAS", "NYS", "AMS")
        private const val TOP_N = 40

        // ETF/ETN 발행사 브랜드 + 명시 키워드. 미국 거래대금 상위 ETF 대부분을 커버.
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
