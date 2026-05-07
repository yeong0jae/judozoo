package at.backend.trading.domain.order

import java.time.Instant

data class ExecutionNotice(
    val kisOrderNo: String,
    val stockCode: String,
    val side: OrderSide,
    val executedQty: Int,
    val executedPrice: Int,
    val timestamp: Instant,
) {
    init {
        require(kisOrderNo.isNotBlank()) { "주문번호는 비어있을 수 없습니다" }
        require(stockCode.isNotBlank()) { "종목코드는 비어있을 수 없습니다" }
        require(executedQty > 0) { "체결 수량은 양수여야 합니다: $executedQty" }
        require(executedPrice > 0) { "체결 단가는 양수여야 합니다: $executedPrice" }
    }
}
