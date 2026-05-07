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

    @Column(length = 20)
    var kisOrderNo: String? = null,

    @Column(length = 5)
    var krxFwdgOrdOrgno: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: OrderStatus,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(length = 500)
    var lastError: String? = null

) : BaseEntity() {

    fun acknowledge(kisOrderNo: String, krxFwdgOrdOrgno: String?) {
        this.kisOrderNo = kisOrderNo
        this.krxFwdgOrdOrgno = krxFwdgOrdOrgno
    }

    fun markFailed(error: String?) {
        this.status = OrderStatus.FAILED
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    /**
     * 응답 파싱 실패 / 네트워크 오류 등 KIS 측 처리 여부가 불확실한 케이스.
     * status는 PENDING 유지 → reconcile이 일별 체결 조회로 실 체결 여부 확인 후 FILLED/FAILED 확정.
     * lastError만 기록해 운영자가 사후 추적 가능.
     */
    fun markUncertain(error: String?) {
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    fun markRetryableFailed(error: String?) {
        this.status = OrderStatus.FAILED
        this.retryCount += 1
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    fun applyExecution(notice: ExecutionNotice, fee: Int, tax: Int): Execution {
        require(notice.kisOrderNo == kisOrderNo) {
            "통보 주문번호가 Order와 일치하지 않습니다: notice=${notice.kisOrderNo}, order=$kisOrderNo"
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

    fun markNeedsManualReview(reason: String?) {
        status = OrderStatus.NEEDS_REVIEW
        lastError = reason?.take(MAX_ERROR_LEN)
    }

    fun reconcileFilled(totalFilledQty: Int) {
        require(totalFilledQty >= 0) { "체결 수량은 0 이상이어야 합니다: $totalFilledQty" }
        filledQty = totalFilledQty
        if (filledQty >= orderQty) status = OrderStatus.FILLED
    }

    fun isReconcilable(): Boolean = status == OrderStatus.PENDING && filledQty == 0

    companion object {
        private const val MAX_ERROR_LEN = 500
    }
}
