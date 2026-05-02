package at.backend.stock.domain

data class StockInfo(
    val stockCode: String,
    val name: String,
) {
    init {
        require(stockCode.isNotBlank()) { "종목코드는 비어있을 수 없습니다" }
        require(name.isNotBlank()) { "종목명은 비어있을 수 없습니다" }
    }
}
