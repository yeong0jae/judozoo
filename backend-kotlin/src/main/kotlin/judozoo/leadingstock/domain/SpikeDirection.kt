package judozoo.leadingstock.domain

/** 스파이크 봉의 방향 — 차트 거래량 막대 색과 같다. 종가 ≥ 시가(빨강)면 매수, 아니면(파랑) 매도. [FLAT]은 옛 기록을 읽으려고만 남겨 둔다. */
enum class SpikeDirection { BUY, SELL, FLAT }
