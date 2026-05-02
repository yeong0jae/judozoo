package at.backend.platform.kis.client

data class KisDailyFill(
    val kisOrderNo: String,
    val executedPrice: Int,
    val executedQty: Int,
)
