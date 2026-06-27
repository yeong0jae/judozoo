package at.backend.leadingstock.domain

/**
 * 통계·전시용 신호 분류 — eventType과 스파이크 방향을 합쳐 하나의 종류로 본다.
 * 같은 VOLUME_SPIKE라도 매수([SPIKE_BUY], 진입 후보)와 매도([SPIKE_SELL], 회피 신호)는 정반대 의미라 분리한다.
 */
enum class SignalKind {
    BREAKOUT,
    BREAKOUT_IMMINENT,
    SPIKE_BUY,
    SPIKE_SELL,
    SPIKE_FLAT,
}
