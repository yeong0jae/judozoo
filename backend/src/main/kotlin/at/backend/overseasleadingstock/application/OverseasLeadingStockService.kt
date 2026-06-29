package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.presentation.response.OverseasDailyCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import at.backend.platform.kis.client.KisOverseasChartClient
import at.backend.platform.kis.client.KisOverseasRankingClient
import org.springframework.stereotype.Service

@Service
class OverseasLeadingStockService(
    private val rankingClient: KisOverseasRankingClient,
    private val chartClient: KisOverseasChartClient,
) {

    /**
     * 미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위 60위.
     * 국내와 동일한 흐름: 통합 거래대금 60위 컷 → ETF 제외 → 거래대금 1~3위는 등락률 무관 항상 포함,
     * 나머지는 당일 등락률이 [minChangeRate] 이상인 것만 통과. (60위 컷·ETF로 결과는 60개 미만일 수 있다)
     */
    fun getRanking(minChangeRate: Double): List<OverseasStockRankItem> {
        val pool = EXCHANGES
            .flatMap { excd -> rankingClient.fetchTradingValueRanking(excd).map { it.toRankItem() } }
            .sortedByDescending { it.tradingValue }
            .take(TOP_N)
            .filterNot { it.isEtf() }

        // 거래대금 1~3위는 시장 톤 기준점으로 항상 포함, 4위부터는 등락률 필터
        val topThree = pool.take(TOP_RANK_ALWAYS_INCLUDED)
        val rest = pool.drop(TOP_RANK_ALWAYS_INCLUDED).filter { it.rate >= minChangeRate }
        return (topThree + rest).mapIndexed { i, item -> item.copy(rank = i + 1) }
    }

    /** 종목 일봉 (일자 오름차순). */
    fun dailyCandles(exchange: String, symbol: String): List<OverseasDailyCandleItem> =
        chartClient.fetchDailyCandles(exchange, symbol)
            .sortedBy { it.date }
            .map {
                OverseasDailyCandleItem(
                    date = it.date.toString(),
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                )
            }

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
        private const val TOP_N = 60
        private const val TOP_RANK_ALWAYS_INCLUDED = 3

        // ETF/ETN 발행사 브랜드 + 명시 키워드. 미국 거래대금 상위 ETF 대부분을 커버.
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
