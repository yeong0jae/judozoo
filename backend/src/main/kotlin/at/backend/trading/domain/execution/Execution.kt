package at.backend.trading.domain.execution

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "executions")
class Execution(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val orderId: Long,

    @Column(nullable = false)
    val executedQty: Int,

    @Column(nullable = false)
    val executedPrice: Int,

    @Column(nullable = false)
    val fee: Int,

    @Column(nullable = false)
    val tax: Int,

) : BaseEntity() {
    init {
        require(executedPrice > 0) { "체결가는 양수여야 합니다: $executedPrice" }
        require(executedQty > 0) { "수량은 양수여야 합니다: $executedQty" }
        require(fee >= 0) { "수수료는 0 이상이어야 합니다: $fee" }
        require(tax >= 0) { "세금은 0 이상이어야 합니다: $tax" }
    }
}
