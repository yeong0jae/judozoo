package at.backend.trading.domain

import java.math.BigDecimal

data class TradingInput(
    val stockCode: String,                  // 종목 코드 (예: "005930")
    val perBuyAmount: Long,                 // 1회 매수 금액 (원)
    val buyIntervalMin: Int,                // 매수 간격 (분)
    val splitSellRatio: BigDecimal,         // 분할 매도 비율 (0 < x < 1)
    val midwayProfitPct: BigDecimal,        // 중도 익절 기준 수익률 (%)
    val breakevenThresholdPct: BigDecimal,  // 본전 매도 활성화 기준 수익률 (%)
    val stopLossPct: BigDecimal,            // 손절 기준 수익률 (양수 입력, 내부에서 음수 변환)
) {
    init {
        val valid = perBuyAmount > 0 &&
            buyIntervalMin > 0 &&
            splitSellRatio > BigDecimal.ZERO && splitSellRatio < BigDecimal.ONE &&
            midwayProfitPct > BigDecimal.ZERO &&
            breakevenThresholdPct > BigDecimal.ZERO &&
            stopLossPct > BigDecimal.ZERO
        if (!valid) throw TradingValidationException(TradingValidationException.ErrorCode.INVALID_PARAMETER)
    }
}
