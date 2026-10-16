package judozoo.overseasleadingstock.domain

import java.time.LocalDateTime
import java.time.LocalTime

/** 미국 정규장. 분봉에는 장전·장후 거래도 섞여 있어 시가는 정규장 시작 뒤 첫 봉에서 읽는다. */
object UsRegularSession {
    /** 정규장 시작(현지 시각) */
    val OPEN: LocalTime = LocalTime.of(9, 30)

    /** 한 거래일치 (현지 시각, 시가) 중 정규장 첫 봉의 시가. 아직 장전이라 없으면 null. */
    fun openPrice(bars: List<Pair<LocalDateTime, Double>>): Double? =
        bars.filter { (at, price) -> !at.toLocalTime().isBefore(OPEN) && price > 0 }.minByOrNull { it.first }?.second
}
