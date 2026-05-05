package at.backend.account.presentation.payload

import java.time.Instant

/**
 * `/topic/account` BALANCE_INVALIDATED 페이로드 — 잔고 변동 가능성 알림.
 * lifecycle CREATED/CLOSED와 자동 동반 발행.
 */
data class BalanceInvalidatedPayload(
    val ts: Instant,
) {
    val type: String = "BALANCE_INVALIDATED"
}
