package at.backend.trading.domain.execution

data class Execution(
    val executedPrice: Int,  // 체결가
    val executedQty: Int,    // 수량
    val fee: Int,            // 수수료
) {
    init {
        require(executedPrice > 0) { "체결가는 양수여야 합니다: $executedPrice" }
        require(executedQty > 0) { "수량은 양수여야 합니다: $executedQty" }
        require(fee >= 0) { "수수료는 0 이상이어야 합니다: $fee" }
    }
}
