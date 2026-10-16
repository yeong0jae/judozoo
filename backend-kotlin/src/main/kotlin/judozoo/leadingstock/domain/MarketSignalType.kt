package judozoo.leadingstock.domain

/** 시장(지수) 시그널 종류. */
enum class MarketSignalType {
    /** 투자자 누적 순매수가 단계(조/천억)를 넘은 전이 */
    NET_BUY_LEVEL,

    /** 누적 순매수 흐름이 정점에서 되돌려 방향이 꺾인 전환 */
    NET_FLOW_TURN,

    /** 지수 5분봉 종가가 20이평을 아래→위로 뚫은 반등 */
    MA_REBOUND,

    /** 지수 5분봉 종가가 20이평을 위→아래로 뚫은 꺾임 */
    MA_BREAKDOWN,
}
