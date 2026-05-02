package at.backend.trading.domain.price

import java.time.Instant

data class PriceTick(
    val stockCode: String,
    val price: Int,
    val timestamp: Instant,
) {
    init {
        require(stockCode.isNotBlank()) { "종목코드는 비어있을 수 없습니다" }
        require(price > 0) { "현재가는 양수여야 합니다: $price" }
    }
}
