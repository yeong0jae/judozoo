package at.backend.market.domain.regime

import java.time.Instant

/** 시장 흐름 선그래프용 한 시점 (당일 시계열). */
data class RegimePoint(
    val asOf: Instant,
    val gap1: Double,
    val gap2: Double?,
)
