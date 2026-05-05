package at.backend.market.presentation.payload

import java.time.Instant

/**
 * `/topic/market` MARKET_MODE 페이로드 — 시세 모드(WS / POLLING) 변경.
 */
data class MarketModePayload(
    val mode: String,
    val ts: Instant,
) {
    val type: String = "MARKET_MODE"

    companion object {
        const val MODE_WS = "WS"
        const val MODE_POLLING = "POLLING"
    }
}
