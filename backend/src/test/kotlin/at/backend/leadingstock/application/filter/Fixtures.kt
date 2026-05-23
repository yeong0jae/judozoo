package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.DailyCandle
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.leadingstock.domain.MinuteCandle
import java.time.LocalDate
import java.time.LocalDateTime

internal fun snapshot(
    stockCode: String = "005930",
    stockName: String = "삼성전자",
    currentPrice: Long = 70_000,
    priceChangeRate: Double = 5.0,
    tradingValueRank: Int = 1,
    accumulatedTradingValue: Long = 1_000_000_000_000L,
    marketCap: Long = 5000,
    openingPrice: Long = 70_000,
    previousClose: Long = 67_000,
    highPrice: Long = 71_000,
    lowPrice: Long = 69_000,
    programNetBuy: Long = 0,
): LeadingStockSnapshot = LeadingStockSnapshot(
    stockCode = stockCode,
    stockName = stockName,
    currentPrice = currentPrice,
    priceChangeRate = priceChangeRate,
    tradingValueRank = tradingValueRank,
    accumulatedTradingValue = accumulatedTradingValue,
    marketCap = marketCap,
    openingPrice = openingPrice,
    previousClose = previousClose,
    highPrice = highPrice,
    lowPrice = lowPrice,
    programNetBuy = programNetBuy,
)

internal fun defaultCriteria(): LeadingStockCriteriaProperties = LeadingStockCriteriaProperties()

internal fun dailyCandle(
    date: LocalDate = LocalDate.of(2026, 5, 23),
    openPrice: Long = 70_000,
    highPrice: Long = 71_000,
    lowPrice: Long = 69_000,
    closePrice: Long = 70_500,
    volume: Long = 1_000_000,
    changeRate: Double = 1.0,
): DailyCandle = DailyCandle(date, openPrice, highPrice, lowPrice, closePrice, volume, changeRate)

internal fun minuteCandle(
    dateTime: LocalDateTime = LocalDateTime.of(2026, 5, 23, 10, 0),
    openPrice: Long = 70_000,
    highPrice: Long = 70_100,
    lowPrice: Long = 69_900,
    closePrice: Long = 70_050,
    volume: Long = 10_000,
    tradingValue: Long = 700_000_000,
): MinuteCandle = MinuteCandle(dateTime, openPrice, highPrice, lowPrice, closePrice, volume, tradingValue)
