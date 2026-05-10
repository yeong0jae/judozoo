package at.backend.trading.domain.cycle

enum class CloseReason {
    TAKE_PROFIT,  // 익절 단계 소진 후 잔여 전량 매도
    STOP_LOSS,    // 손절
    BREAKEVEN,    // 본전 매도
    TREND_BREAK,  // 추세 꺾임
    CANCELLED,    // 사용자 취소
    NO_FILL,      // 3회 매수 시도했는데 체결 수량 0
    UNCLOSED,     // 시스템 재시작으로 강제 마감
}
