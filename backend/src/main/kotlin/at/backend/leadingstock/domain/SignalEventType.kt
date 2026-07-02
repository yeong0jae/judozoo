package at.backend.leadingstock.domain

/** 적재 대상 시그널 전이 종류. */
enum class SignalEventType {
    /** 당일 전고점을 처음 돌파한 순간(갭 0 이하 진입). */
    BREAKOUT,

    /** 전고점까지 1% 미만으로 처음 접근한 순간(돌파 직전 경고). */
    BREAKOUT_IMMINENT,

    /** 1분 거래대금 배율이 임계(3배)를 처음 넘긴 순간(수급 유입). */
    VOLUME_SPIKE,

    /** 직전 확정 5분봉 종가가 5분봉 20이평을 아래에서 위로 처음 뚫은 순간(돌림). */
    MA20_CROSS,
}
