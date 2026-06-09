package at.backend.leadingstock.domain

import java.time.LocalDate

/** 일봉 모음 — 컬렉션 단위 거래량 지표를 담는다. 입력은 최신순(내림차순)을 가정. */
class DailyCandles(private val candles: List<DailyCandle>) {

    /**
     * 풀데이 상대거래량(RVOL): 당일 누적 거래량 / 직전 [lookback]거래일 평균 거래량.
     *
     * [today] 캔들(장중 누적 진행 중)을 직전 거래일들과 비교해 "평소의 몇 배인가"를 낸다.
     * 당일 캔들이 없거나(장 시작 전 등) 베이스라인이 비면 null.
     * 주의: 장 초반엔 당일 누적이 아직 적어 1 미만으로 낮게 나온다(풀데이 방식의 한계).
     */
    fun relativeVolume(today: LocalDate, lookback: Int): Double? {
        val todayVolume = candles.firstOrNull { it.date == today }?.volume ?: return null
        val baseline = candles.filter { it.date < today }.take(lookback).map { it.volume }
        if (baseline.isEmpty()) return null
        val avg = baseline.average()
        if (avg <= 0) return null
        return todayVolume / avg
    }
}
