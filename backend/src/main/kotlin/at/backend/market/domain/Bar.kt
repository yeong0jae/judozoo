package at.backend.market.domain

import java.time.Instant

data class Bar(
    val stockCode: String,
    val openPrice: Int,
    val closePrice: Int,
    val startTime: Instant,
    val endTime: Instant,
) {
    init {
        require(stockCode.isNotBlank()) { "종목코드는 비어있을 수 없습니다" }
        require(openPrice > 0) { "시가는 양수여야 합니다: $openPrice" }
        require(closePrice > 0) { "종가는 양수여야 합니다: $closePrice" }
        require(startTime < endTime) { "봉 시작 시각은 종료 시각보다 앞서야 합니다" }
    }
}
