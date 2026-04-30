package at.backend.trading.domain.rule

data class Execution(
    val executedPrice: Int,
    val executedQty: Int,
    val fee: Int,
)
