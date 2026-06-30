package at.backend.overseasleadingstock.presentation.response

import java.time.LocalDateTime

/** 해외 돌파 현황 한 종목 — 돌파선(누적 분봉 최고가)·형성시각·돌파까지 갭%. 가격 단위 USD. */
data class OverseasBreakoutRadarItem(
    val exchange: String,
    val symbol: String,
    val name: String,
    val price: Double,
    val rate: Double,           // 당일 등락률(%)
    val tradingValue: Double,   // 당일 누적 거래대금(USD)
    val dayHigh: Double,        // 돌파선
    val peakAt: LocalDateTime,  // 돌파선 형성 분봉 시각(한국 벽시계)
    val gapRate: Double,        // 돌파까지 남은 상승률(%), 돌파 시 음수
)
