package at.backend.overseasleadingstock.application

import at.backend.platform.kis.client.KisOverseasChartClient.OverseasMinuteCandle
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * 해외 종목 1분봉 라이브 누적 스토어(메모리).
 *
 * KIS 분봉은 1회 120건(≈2시간)뿐이라, 폴러가 매 폴 최신 페이지를 받아 여기에 분 단위로 병합한다.
 * 장중 폴러가 계속 돌면 그날 전체 분봉이 쌓여, 돌파선·차트가 누적분을 읽는다(추가 호출 0).
 * 국내 지수 분봉(IndexMinuteCandleStore)과 같은 라이브 누적 방식.
 */
@Component
class OverseasMinuteCandleStore {

    // key("EXCD:SYMB") → (시각 → 봉). 시각 정렬 맵이라 항상 시간 오름차순으로 읽힌다.
    private val byKey = ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, OverseasMinuteCandle>>()

    private fun key(exchange: String, symbol: String) = "$exchange:$symbol"

    /** 받은 봉을 분 단위로 병합(같은 시각은 최신값으로 덮어씀 — 형성 중인 봉 갱신). */
    fun merge(exchange: String, symbol: String, candles: List<OverseasMinuteCandle>) {
        if (candles.isEmpty()) return
        val map = byKey.getOrPut(key(exchange, symbol)) { ConcurrentSkipListMap() }
        candles.forEach { map[it.dateTime] = it }
    }

    /** 누적된 봉 시간 오름차순. 없으면 빈 리스트. */
    fun candles(exchange: String, symbol: String): List<OverseasMinuteCandle> =
        byKey[key(exchange, symbol)]?.values?.toList() ?: emptyList()

    /** 이미 누적분이 있는지 — 첫 등장 종목만 2거래일 seed하기 위한 판별. */
    fun has(exchange: String, symbol: String): Boolean =
        byKey[key(exchange, symbol)]?.isNotEmpty() == true

    /** 일자 전환 시 [keepDate] 이전 봉을 비운다(메모리 누수 방지). */
    fun clearBefore(keepDate: LocalDate) {
        byKey.values.forEach { map ->
            map.keys.filter { it.toLocalDate() < keepDate }.forEach { map.remove(it) }
        }
        byKey.entries.removeIf { it.value.isEmpty() }
    }
}
