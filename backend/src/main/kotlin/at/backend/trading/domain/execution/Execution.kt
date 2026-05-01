package at.backend.trading.domain.execution

data class Execution(
    val executedPrice: Int,  // 체결가
    val executedQty: Int,    // 수량
    val fee: Int,            // 수수료
)
