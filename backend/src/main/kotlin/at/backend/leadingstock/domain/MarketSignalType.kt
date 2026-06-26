package at.backend.leadingstock.domain

/** 시장(지수) 시그널 종류. */
enum class MarketSignalType {
    /** 투자자(외인/기관/개인) 당일 누적 순매수가 단계(조/천억)를 넘은 전이. */
    NET_BUY_LEVEL,

    /** 지수 1분봉이 같은 색(양봉/음봉)으로 5연속 이상 이어진 전이. */
    CANDLE_STREAK,
}
