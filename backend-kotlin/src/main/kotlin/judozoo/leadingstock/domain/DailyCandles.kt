package judozoo.leadingstock.domain

import java.time.LocalDate

/** 일봉 모음 — 입력은 **최신순(내림차순)**을 가정한다. */
class DailyCandles(private val candles: List<DailyCandle>) {

    /**
     * 풀데이 상대거래량(RVOL) = 현재 누적 거래량 / 직전 [lookback]거래일 평균. 현재 누적은 시세에서 받아 넘긴다.
     *
     * 장 초반엔 당일 누적이 아직 적어 1 미만으로 낮게 나온다(풀데이 방식의 한계).
     */
    fun relativeVolumeFrom(currentVolume: Long?, today: LocalDate, lookback: Int): Double? {
        if (currentVolume == null) return null
        val baseline = candles.filter { it.date < today }.take(lookback).map { it.volume }
        if (baseline.isEmpty()) return null
        val avg = baseline.average()
        if (avg <= 0) return null
        return currentVolume / avg
    }
}
