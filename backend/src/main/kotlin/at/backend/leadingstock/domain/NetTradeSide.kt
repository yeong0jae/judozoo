package at.backend.leadingstock.domain

/** 누적 순매수의 방향 — 순매수(+)/순매도(−). 중립(0)은 방향 없음으로 본다. */
enum class NetTradeSide {
    BUY,
    SELL;

    fun opposite(): NetTradeSide = if (this == BUY) SELL else BUY
}
