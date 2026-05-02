package at.backend.trading.domain.order

import at.backend.library.jpa.BaseEntity
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

    @Column(nullable = false, length = 20)
    val trigger: String,

    @Column(nullable = false)
    val orderQty: Int,

    @Column(nullable = false)
    var filledQty: Int = 0,

    @Column(nullable = false, length = 10)
    val orderType: String = "MARKET",

    @Column(length = 20)
    var kisOrderNo: String? = null,

    @Column(nullable = false, length = 15)
    var status: String,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(length = 500)
    var lastError: String? = null

) : BaseEntity()
