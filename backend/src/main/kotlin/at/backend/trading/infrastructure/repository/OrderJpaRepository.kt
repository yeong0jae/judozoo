package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.order.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByCycleId(cycleId: Long): List<Order>

    fun findByKisOrderNo(kisOrderNo: String): Order?

    @Query(
        "SELECT COALESCE(SUM(o.orderQty - o.filledQty), 0) FROM Order o " +
            "WHERE o.cycleId = :cycleId AND o.side = 'SELL' AND o.status = 'PENDING'"
    )
    fun inFlightSellUnfilled(@Param("cycleId") cycleId: Long): Int

    @Query(
        "SELECT o FROM Order o " +
            "WHERE o.cycleId = :cycleId AND o.side = 'SELL' AND o.status = 'PENDING'"
    )
    fun findInFlightSells(@Param("cycleId") cycleId: Long): List<Order>

    @Query(
        "SELECT o FROM Order o " +
            "WHERE o.cycleId = :cycleId AND o.side = 'BUY' AND o.status = 'PENDING'"
    )
    fun findInFlightBuys(@Param("cycleId") cycleId: Long): List<Order>
}
