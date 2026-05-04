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

    @Column(nullable = false, length = 4)
    val side: String,

    @Column(name = "trigger_type", nullable = false, length = 20)
    val trigger: String,

    @Column(nullable = false)
    val orderQty: Int,

    @Column(nullable = false)
    var filledQty: Int = 0,

    @Column(nullable = false, length = 10)
    val orderType: String = "MARKET",

    @Column(length = 20)
    var kisOrderNo: String? = null,

    @Column(length = 5)
    var krxFwdgOrdOrgno: String? = null,

    @Column(nullable = false, length = 15)
    var status: String,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(length = 500)
    var lastError: String? = null

) : BaseEntity() {

    fun acknowledge(kisOrderNo: String, krxFwdgOrdOrgno: String) {
        this.kisOrderNo = kisOrderNo
        this.krxFwdgOrdOrgno = krxFwdgOrdOrgno
    }

    fun markFailed(error: String?) {
        this.status = STATUS_FAILED
        this.lastError = error?.take(MAX_ERROR_LEN)
    }

    fun markRetryableFailed(error: String?) {
        this.status = STATUS_FAILED
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
        if (filledQty >= orderQty) status = STATUS_FILLED
        return Execution(
            orderId = id,
            executedQty = notice.executedQty,
            executedPrice = notice.executedPrice,
            fee = fee,
            tax = tax,
        )
    }

    fun isFullyFilled(): Boolean = status == STATUS_FILLED

    fun markCancelled() {
        status = STATUS_CANCELLED
    }

    companion object {
        private const val STATUS_FILLED = "FILLED"
        private const val STATUS_FAILED = "FAILED"
        private const val STATUS_CANCELLED = "CANCELLED"
        private const val MAX_ERROR_LEN = 500
    }
}
