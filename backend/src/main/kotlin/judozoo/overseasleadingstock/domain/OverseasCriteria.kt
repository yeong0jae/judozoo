package judozoo.overseasleadingstock.domain

import java.util.Locale

data class OverseasCriteria(
    val topN: Int = 40,
    val minChangeRate: Double = 5.0,
    val minMarketCapUsd: Long = 2_000_000_000L,
    val maxOpeningGap: Double = 7.0,
) {
    fun evaluate(stock: OverseasStockRank, marketCap: Long?, sessionOpen: Double?): List<FilterResult> {
        val fromOpen = sessionOpen?.let { (stock.price - it) / it * 100 }
        val prevClose = stock.previousClose
        val gap = if (sessionOpen != null && prevClose > 0) (sessionOpen - prevClose) / prevClose * 100 else null
        return listOf(
            FilterResult("거래대금순위", "통합 상위 ${topN}위 이내", "${stock.rank}위", stock.rank <= topN, stock.rank.toDouble(), topN.toDouble()),
            FilterResult(
                "당일 등락률", "${minChangeRate.toInt()}% 이상", "%+.2f%%".fmt(stock.rate),
                stock.rate >= minChangeRate, stock.rate, minChangeRate,
            ),
            FilterResult(
                "시가총액", "$${minMarketCapUsd / 1_000_000_000}B 이상", marketCap?.let(::formatUsdCap) ?: "조회 불가",
                marketCap != null && marketCap >= minMarketCapUsd,
            ),
            FilterResult(
                "시가 대비 현재가", "현재가 ≥ 시가", fromOpen?.let { "%+.2f%%".fmt(it) } ?: "시가 없음",
                fromOpen != null && fromOpen >= 0, fromOpen, 0.0,
            ),
            FilterResult(
                "시초가", "시초가 $maxOpeningGap% 이하",
                if (gap != null) "$%,.2f (%+.2f%%)".fmt(sessionOpen, gap) else "시초가 없음",
                gap != null && gap <= maxOpeningGap, gap, maxOpeningGap,
            ),
        )
    }

    companion object {
        fun formatUsdCap(usd: Long): String =
            if (usd >= 1_000_000_000_000) "$%.2fT".fmt(usd / 1_000_000_000_000.0) else "$%,dB".fmt(usd / 1_000_000_000)

        private fun String.fmt(vararg args: Any?) = String.format(Locale.ROOT, this, *args)
    }
}
