package at.backend.trading.domain.order

enum class OrderStatus {
    PENDING,
    FILLED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
}
