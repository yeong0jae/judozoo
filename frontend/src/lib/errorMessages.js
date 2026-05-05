// 백엔드 errorCode → 한글 메시지. 표시 위치는 호출자가 결정 (인라인/배너/토스트).
// 참고: backend/src/main/kotlin/at/backend/trading/domain/TradingValidationException.kt
const messages = {
    // 9개 trading validation errorCodes
    INVALID_PARAMETER: "입력값을 확인해주세요",
    STOCK_NOT_FOUND: "해당 종목을 찾을 수 없습니다",
    PRICE_BELOW_ONE_SHARE: "1주 가격에 미달합니다",
    INSUFFICIENT_BALANCE: "잔고가 부족합니다",
    DUPLICATE_COMMAND: "같은 종목으로 진행 중인 명령이 있습니다",
    CUTOFF_PASSED: "신규 명령 마감 — 15:20 컷오프를 지났습니다",
    HOLIDAY: "휴장일에는 명령을 받을 수 없습니다",
    OUT_OF_TRADING_HOURS: "거래시간이 아닙니다 (09:00~15:30)",
    COMMAND_GATE_CLOSED: "명령 접수가 일시 중단되었습니다",
    // HTTP / 일반 오류
    ALREADY_CLOSED: "이미 종료된 명령입니다",
    NOT_FOUND: "항목을 찾을 수 없습니다",
    NETWORK_ERROR: "서버 연결 실패 — 잠시 후 다시 시도해주세요",
    SERVER_ERROR: "서버 오류 — 잠시 후 다시 시도해주세요",
};
export function errorMessage(code) {
    return messages[code] ?? `알 수 없는 오류 (${code})`;
}
