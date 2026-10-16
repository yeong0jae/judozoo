package judozoo.leadingstock.domain

import judozoo.library.KoreanMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

sealed class StockFilter(val name: String) {
    abstract fun passes(stock: LeadingStockSnapshot): Boolean

    abstract fun evaluate(stock: LeadingStockSnapshot): FilterResult

    protected fun result(stock: LeadingStockSnapshot, description: String, actual: String, value: Double? = null, threshold: Double? = null) =
        FilterResult(name, description, actual, passes(stock), value, threshold)

    class TradingValueRank(private val criteria: LeadingStockCriteria) : StockFilter("거래대금순위") {
        override fun passes(stock: LeadingStockSnapshot) = stock.tradingValueRank <= criteria.maxTradingValueRank

        override fun evaluate(stock: LeadingStockSnapshot) = result(
            stock, "상위 ${criteria.maxTradingValueRank}위 이내", "${stock.tradingValueRank}위",
            stock.tradingValueRank.toDouble(), criteria.maxTradingValueRank.toDouble(),
        )
    }

    class DailyPriceChange(private val criteria: LeadingStockCriteria) : StockFilter("당일등락률") {
        override fun passes(stock: LeadingStockSnapshot) = stock.priceChangeRate >= criteria.minDailyPriceChangeRate

        override fun evaluate(stock: LeadingStockSnapshot) = result(
            stock, "${criteria.minDailyPriceChangeRate}% 이상", signed(stock.priceChangeRate),
            stock.priceChangeRate, criteria.minDailyPriceChangeRate,
        )
    }

    class MarketCap(private val criteria: LeadingStockCriteria) : StockFilter("시가총액") {
        override fun passes(stock: LeadingStockSnapshot) = stock.marketCap >= criteria.minMarketCap

        override fun evaluate(stock: LeadingStockSnapshot) = result(
            stock, "${KoreanMoney.format(criteria.minMarketCap * EOK)} 이상", KoreanMoney.format(stock.marketCap * EOK),
        )
    }

    data object PriceAboveOpen : StockFilter("시가 대비 현재가") {
        override fun passes(stock: LeadingStockSnapshot) = stock.openingPrice > 0 && stock.currentPrice >= stock.openingPrice

        override fun evaluate(stock: LeadingStockSnapshot): FilterResult {
            val rate = if (stock.openingPrice > 0) (stock.currentPrice - stock.openingPrice).toDouble() / stock.openingPrice * 100 else null
            return result(stock, "현재가 ≥ 시가", rate?.let(::signed) ?: "시가 없음", rate, 0.0)
        }
    }

    class PrevDayClose(private val criteria: LeadingStockCriteria, private val daily: List<DailyCandle>) : StockFilter("전일 등락률") {
        private val rate = daily.getOrNull(1)?.changeRate

        override fun passes(stock: LeadingStockSnapshot) = rate != null && rate <= criteria.maxPrevCloseChangeRate

        override fun evaluate(stock: LeadingStockSnapshot) = result(
            stock, "${criteria.maxPrevCloseChangeRate}% 이하", rate?.let(::signed) ?: "데이터 부족", rate, criteria.maxPrevCloseChangeRate,
        )
    }

    class OpeningPrice(private val criteria: LeadingStockCriteria, private val daily: List<DailyCandle>) : StockFilter("시초가") {
        private val rate: Double? = run {
            if (daily.size < 2) return@run null
            val (today, yesterday) = daily
            if (today.openPrice <= 0 || yesterday.closePrice <= 0) null
            else (today.openPrice - yesterday.closePrice).toDouble() / yesterday.closePrice * 100
        }

        override fun passes(stock: LeadingStockSnapshot) = rate != null && rate <= criteria.maxOpeningPriceChangeRate

        override fun evaluate(stock: LeadingStockSnapshot): FilterResult {
            val actual = when {
                daily.size < 2 -> "데이터 부족"
                rate == null -> "시초가 없음"
                else -> "${KoreanMoney.formatPrice(daily[0].openPrice)} (${signed(rate)})"
            }
            return result(stock, "시초가 ${criteria.maxOpeningPriceChangeRate}% 이하", actual, rate, criteria.maxOpeningPriceChangeRate)
        }
    }

    data object EtfExclusion : StockFilter("ETF/ETN 제외") {
        private val BRAND_PREFIXES = listOf(
            "KODEX ", "TIGER ", "KOSEF ", "KBSTAR ", "ARIRANG ",
            "ACE ", "HANARO ", "SOL ", "RISE ", "WOORI ",
            "KOACT ", "히어로즈 ", "PLUS ", "MASTER ",
        )

        override fun passes(stock: LeadingStockSnapshot) =
            BRAND_PREFIXES.none { stock.stockName.startsWith(it) } && " ETN" !in stock.stockName

        override fun evaluate(stock: LeadingStockSnapshot) =
            result(stock, "개별 종목만 (ETF/ETN 제외)", if (passes(stock)) "개별 종목" else "ETF/ETN")
    }

    data object SpacExclusion : StockFilter("스팩 제외") {
        override fun passes(stock: LeadingStockSnapshot) = "스팩" !in stock.stockName

        override fun evaluate(stock: LeadingStockSnapshot) =
            result(stock, "개별 종목만 (스팩 제외)", if (passes(stock)) "개별 종목" else "스팩")
    }

    class DailyHighPosition(private val criteria: LeadingStockCriteria, daily: List<DailyCandle>, asOf: LocalDate) :
        StockFilter("최근 고가 대비 현재가") {
        private val maxHigh: Long? = daily.filter { it.date < asOf }.sortedByDescending { it.date }.take(60)
            .maxOfOrNull { it.highPrice }?.takeIf { it > 0 }

        private fun dropRate(stock: LeadingStockSnapshot) = maxHigh?.let { (stock.currentPrice - it).toDouble() / it * 100 }

        override fun passes(stock: LeadingStockSnapshot) = dropRate(stock)?.let { it >= criteria.maxHighPositionDropRate } ?: false

        override fun evaluate(stock: LeadingStockSnapshot): FilterResult {
            val rate = dropRate(stock)
            return result(
                stock, "전일까지 최근 60거래일 고가 대비 현재가 ${criteria.maxHighPositionDropRate}% 이상",
                rate?.let { "${fixed(it)}%" } ?: "데이터 없음", rate, criteria.maxHighPositionDropRate,
            )
        }
    }

    class ProgramNetBuy(private val criteria: LeadingStockCriteria, private val netBuy: Long) : StockFilter("프로그램 양매수") {
        override fun passes(stock: LeadingStockSnapshot) = netBuy >= criteria.minProgramNetBuy

        override fun evaluate(stock: LeadingStockSnapshot) = result(
            stock, "${KoreanMoney.format(criteria.minProgramNetBuy * MILLION)} 이상", KoreanMoney.formatWithMan(netBuy * MILLION),
        )
    }

    companion object {
        private const val EOK = 100_000_000L
        private const val MILLION = 1_000_000L

        private fun fixed(value: Double, sign: Boolean = false): String =
            String.format(Locale.ROOT, if (sign) "%+.2f" else "%.2f", BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN))

        private fun signed(value: Double) = "${fixed(value, sign = true)}%"
    }
}
