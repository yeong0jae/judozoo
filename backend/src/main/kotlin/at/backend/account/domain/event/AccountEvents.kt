package at.backend.account.domain.event

import java.time.Instant

/**
 * Account 도메인 이벤트.
 * `BalanceInvalidated`는 trading lifecycle CREATED/CLOSED와 함께 발행되어
 * 매매 명령 화면이 잔고를 재조회하도록 트리거.
 */

data class BalanceInvalidated(
    val ts: Instant,
)
