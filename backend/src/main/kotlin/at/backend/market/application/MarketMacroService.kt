package at.backend.market.application

import at.backend.platform.yahoo.client.YahooChartClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * 매크로 지표 — 원달러 환율·WTI 유가. 야후 파이낸스.
 * WTI는 심볼 "CL=F"만 넘기면 야후가 근월물을 물려준다(나스닥 선물과 동일).
 * 환율은 24시간 돌지만 유가는 CME 정산 휴식(06:00~07:00 KST)엔 멈춰 있다.
 */
@Service
class MarketMacroService(
    private val client: YahooChartClient,
) {

    /** 원달러·WTI 시세 — 둘 중 하나만 실패해도 나머지는 살려서 준다. */
    @Cacheable("macroQuotes")
    fun quotes(): MacroQuotes = MacroQuotes(
        usdKrw = quoteOf(MacroTarget.USD_KRW),
        wti = quoteOf(MacroTarget.WTI),
    )

    /** [target] 캔들 — interval "1m"(최근 2일)/"1d"(최근 6개월). */
    @Cacheable("macroCandles", key = "#target.name() + #interval", unless = "#result.isEmpty()")
    fun candles(target: MacroTarget, interval: String): List<YahooChartClient.YahooBar> = when (interval) {
        "1m" -> client.fetchCandles(target.symbol, "1m", "2d")
        "1d" -> client.fetchCandles(target.symbol, "1d", "6mo")
        else -> emptyList()
    }

    private fun quoteOf(target: MacroTarget): MacroQuote? {
        val q = client.fetchQuote(target.symbol) ?: return null
        val change = q.price - q.prevClose
        return MacroQuote(
            price = q.price,
            prevClose = q.prevClose,
            priceChange = change,
            changeRate = if (q.prevClose == 0.0) 0.0 else change / q.prevClose * 100,
        )
    }
}

/** 매크로 상세가 다루는 대상. 야후 심볼을 여기 한 곳에만 둔다. */
enum class MacroTarget(val symbol: String) {
    USD_KRW("KRW=X"), // 원달러 환율
    WTI("CL=F"), // WTI 원유 근월물
}

/** 매크로 시세 묶음 — 상단 스트립이 한 칸에 둘 다 그린다. */
data class MacroQuotes(
    val usdKrw: MacroQuote?,
    val wti: MacroQuote?,
)

/** 매크로 지표 시세. */
data class MacroQuote(
    val price: Double,
    val prevClose: Double, // 전일 종가
    val priceChange: Double, // 전일 대비
    val changeRate: Double, // 전일 대비 등락률(%)
)
