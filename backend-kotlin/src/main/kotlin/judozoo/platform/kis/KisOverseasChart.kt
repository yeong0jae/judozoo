package judozoo.platform.kis

import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * KIS 해외주식 분봉·일봉.
 *
 * - 분봉(HHDFS76950200): 1회 120건. **다음조회 커서 `KEYB`는 현지시각(xymd+xhms) 기준**이며
 *   마지막 봉에서 1분을 뺀 값을 넣는다. 한국시각(kymd+khms)을 넣으면 엉뚱한 구간이 온다.
 * - 일봉(HHDFS76240000): 1회 100건, 한 페이지로 끝난다.
 *
 * 캔들의 시각 자체는 **한국기준(kymd+khms)**을 쓴다. 커서용 현지시각과 혼동하지 않는다.
 * 분봉은 캐시하지 않는다 — 해외 주도주가 종목별로 들고 있다가 새 봉만 이어 받는다.
 */
@Component
class KisOverseasChart(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class OverseasMinuteCandle(
        /** 한국시각 */
        val dateTime: LocalDateTime,
        /** 현지 영업일(tymd) — 미국 장은 한국 자정을 넘나들어 한국 날짜로는 하루가 쪼개진다 */
        val tradingDay: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val tradingValue: Double,
    )

    data class OverseasDailyCandle(
        val date: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
    )

    /** 뒷페이지에서 끊겼다. 그때까지 받은 봉은 [candles]에 있다 — 없는 것보다 낫지만 온전하지 않다. */
    class MinutePagesInterrupted(message: String, val candles: List<OverseasMinuteCandle>, cause: Throwable) :
        RuntimeException(message, cause)

    private val dailyCandles = TtlCache<Pair<String, String>, List<OverseasDailyCandle>>(
        "kisOverseasDailyCandles", Duration.ofSeconds(60), maxSize = 60,
    )

    /**
     * 최신부터 거꾸로 받은 1분봉.
     *
     * - [since]가 없으면 최근 [SESSION_DAYS]거래일. 현지영업일(tymd)이 그보다 많이 보이면 멈추고 최신 거래일만 남긴다
     * - [since](한국시각)가 있으면 그 시각 이후 봉만 — 그보다 이른 봉이 보이면 멈춘다. 이어 받기용이라 대개 첫 페이지에서 끝난다
     *
     * 첫 페이지가 실패하면 예외를 올린다. 뒷페이지가 실패하면 받은 데까지를 [MinutePagesInterrupted]에 담아 올린다.
     */
    fun fetchMinuteCandles(excd: String, symb: String, since: LocalDateTime? = null): List<OverseasMinuteCandle> {
        val collected = mutableListOf<JsonNode>()
        val tradingDays = LinkedHashSet<String>() // 등장 순서를 보존하되 중복은 제외
        var keyb = ""
        var next = ""

        for (page in 0 until MAX_MINUTE_PAGES) {
            val items = try {
                fetchMinutePage(excd, symb, next, keyb)
            } catch (e: Exception) {
                if (page == 0) throw e
                log.warn("KIS 해외 분봉 뒷페이지 실패 — 받은 데까지만 ({}:{}, {}페이지)", excd, symb, page + 1)
                throw MinutePagesInterrupted(e.message.orEmpty(), finish(collected, tradingDays, since), e)
            }
            if (items.isEmpty()) break
            collected += items
            items.mapNotNull { it["tymd"]?.asString()?.trim()?.takeIf(String::isNotEmpty) }.forEach { tradingDays += it }
            if (since == null && tradingDays.size > SESSION_DAYS) break // 다음 거래일까지 받았으니 충분
            if (since != null && items.any { toMinuteCandle(it)?.dateTime?.isBefore(since) == true }) break // 이미 가진 구간에 닿았다

            // 페이지는 최신→과거라 마지막이 가장 이른 봉
            val cursor = parseDateTime(items.last()["xymd"]?.asString(), items.last()["xhms"]?.asString()) ?: break
            keyb = cursor.minusMinutes(1).format(MINUTE_FORMAT)
            next = "1"
        }
        return finish(collected, tradingDays, since)
    }

    /** 최근 100거래일 일봉. 최신→과거 순. 실패하면 빈 목록 — 차트만 짧아지고 끝이라 호출측을 깨우지 않는다. */
    fun fetchDailyCandles(excd: String, symb: String): List<OverseasDailyCandle> =
        dailyCandles.get(excd to symb) { fetchDailyPage(excd, symb).mapNotNull(::toDailyCandle) }

    private fun finish(collected: List<JsonNode>, tradingDays: Set<String>, since: LocalDateTime?): List<OverseasMinuteCandle> {
        val candles = collected.mapNotNull(::toMinuteCandle)
        if (since != null) return candles.filter { !it.dateTime.isBefore(since) }
        val keep = tradingDays.sortedDescending().take(SESSION_DAYS).toSet()
        return candles.filter { it.tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE) in keep }
    }

    /**
     * 실패 시 예외를 올린다 — 페이징 도중 조용히 잘리면 봉이 비는 걸 눈치채기 어렵다.
     * **오류 본문의 코드·메시지를 싣는다.** KIS는 한도 초과도 HTTP 500으로 준다 — 상태 코드만으로는 무엇인지 모른다.
     */
    private fun fetchMinutePage(excd: String, symb: String, next: String, keyb: String): List<JsonNode> {
        val response = client.get(
            "/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice", "HHDFS76950200",
            mapOf(
                "AUTH" to "", "EXCD" to excd, "SYMB" to symb, "NMIN" to "1",
                "PINC" to "1", // 전일포함 — 장 초반에도 직전 세션 봉으로 채움
                "NEXT" to next, "NREC" to "120", "FILL" to "", "KEYB" to keyb,
            ),
        )
        if (!response.ok) {
            throw IllegalStateException(
                "KIS 해외 분봉 오류: HTTP ${response.status} ${response.msgCd} ${response.msg1} ($excd:$symb, KEYB=${keyb.ifEmpty { "-" }})",
            )
        }
        return response.body!!["output2"]?.values()?.toList().orEmpty()
    }

    private fun fetchDailyPage(excd: String, symb: String): List<JsonNode> = try {
        val response = client.get(
            "/uapi/overseas-price/v1/quotations/dailyprice", "HHDFS76240000",
            mapOf(
                "AUTH" to "", "EXCD" to excd, "SYMB" to symb,
                "GUBN" to "0", // 0:일
                "BYMD" to "", // 공란=오늘
                "MODP" to "1", // 수정주가 반영
                "KEYB" to "",
            ),
        )
        if (response.ok) {
            response.body!!["output2"]?.values()?.toList().orEmpty()
        } else {
            log.error("KIS 해외 일봉 오류: HTTP {} {} ({}:{})", response.status, response.msg1, excd, symb)
            emptyList()
        }
    } catch (e: Exception) {
        log.error("KIS 해외 일봉 조회 실패 ({}:{})", excd, symb, e)
        emptyList()
    }

    private fun toMinuteCandle(item: JsonNode): OverseasMinuteCandle? {
        // 봉의 시각은 한국기준(kymd+khms). 커서용 현지시각과 다르다
        val at = parseDateTime(item["kymd"]?.asString(), item["khms"]?.asString()) ?: return null
        val day = parseDate(item["tymd"]?.asString()) ?: return null
        return OverseasMinuteCandle(
            dateTime = at, tradingDay = day,
            open = item.double("open"), high = item.double("high"), low = item.double("low"), close = item.double("last"),
            volume = item.long("evol"), tradingValue = item.double("eamt"),
        )
    }

    private fun toDailyCandle(item: JsonNode): OverseasDailyCandle? {
        val date = parseDate(item["xymd"]?.asString()) ?: return null
        return OverseasDailyCandle(
            date = date, open = item.double("open"), high = item.double("high"), low = item.double("low"),
            close = item.double("clos"), volume = item.long("tvol"),
        )
    }

    companion object {
        /** 분봉 표시 거래일 수 (최신일 프리~애프터 + 직전일 정규장) */
        const val SESSION_DAYS = 2

        /** 2거래일(~1,350분/120) ≈ 12페이지에 여유 */
        const val MAX_MINUTE_PAGES = 20

        private val MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        private fun parseDateTime(ymd: String?, hms: String?): LocalDateTime? {
            if (ymd == null || hms == null) return null
            return runCatching { LocalDateTime.parse(ymd.trim() + hms.trim().padStart(6, '0'), MINUTE_FORMAT) }.getOrNull()
        }

        private fun parseDate(ymd: String?): LocalDate? =
            ymd?.let { runCatching { LocalDate.parse(it.trim(), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }

        private fun JsonNode.double(field: String) = get(field)?.asString()?.trim()?.toDoubleOrNull() ?: 0.0
        private fun JsonNode.long(field: String) = get(field)?.asString()?.trim()?.toLongOrNull() ?: 0L
    }
}
