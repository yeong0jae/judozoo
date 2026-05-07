package at.backend.trading.domain.order

import at.backend.library.jpa.BaseEntity
import at.backend.trading.domain.execution.Execution
import jakarta.persistence.*

@Entity
@Table(name = "orders")
class Order(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val cycleId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    val side: OrderSide,

    @Column(name = "trigger_type", nullable = false, length = 20)
    val trigger: String,

    @Column(nullable = false)
    val orderQty: Int,

    @Column(nullable = false)
    var filledQty: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val orderType: OrderType = OrderType.MARKET,

    @Column(name = "kis_order_no", length = 20)
    var orderNo: String? = null,

    @Column(name = "krx_fwdg_ord_orgno", length = 5)
    var fwdgOrdOrgno: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: OrderStatus,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(length = 500)
    var lastError: String? = null

) : BaseEntity() {

    fun acknowledge(orderNo: String, fwdgOrdOrgno: String?) {
        this.orderNo = orderNo
        this.fwdgOrdOrgno = fwdgOrdOrgno
    }

    fun markFailed(error: String?) {
        this.status = OrderStatus.FAILED
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    fun markRetryableFailed(error: String?) {
        this.status = OrderStatus.FAILED
        this.retryCount += 1
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    fun applyExecution(notice: ExecutionNotice, fee: Int, tax: Int): Execution {
        require(notice.orderNo == orderNo) {
            "통보 주문번호가 Order와 일치하지 않습니다: notice=${notice.orderNo}, order=$orderNo"
        }
        require(notice.side == side) {
            "통보 side가 Order와 일치하지 않습니다: notice=${notice.side}, order=$side"
        }
        filledQty += notice.executedQty
        if (filledQty >= orderQty) status = OrderStatus.FILLED
        return Execution(
            orderId = id,
            executedQty = notice.executedQty,
            executedPrice = notice.executedPrice,
            fee = fee,
            tax = tax,
        )
    }

    fun isFullyFilled(): Boolean = status == OrderStatus.FILLED

    fun markCancelled() {
        status = OrderStatus.CANCELLED
    }

    companion object {
        private const val MAX_ERROR_LEN = 500
    }
}
