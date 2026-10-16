package judozoo.platform.kis

import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class KisOverseasChart(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class OverseasMinuteCandle(
        val dateTime: LocalDateTime,
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

    class MinutePagesInterrupted(message: String, val candles: List<OverseasMinuteCandle>, cause: Throwable) :
        RuntimeException(message, cause)

    private val dailyCandles = TtlCache<Pair<String, String>, List<OverseasDailyCandle>>(
        "kisOverseasDailyCandles", Duration.ofSeconds(60), maxSize = 60,
    )

    fun fetchMinuteCandles(excd: String, symb: String, since: LocalDateTime? = null): List<OverseasMinuteCandle> {
        val collected = mutableListOf<JsonNode>()
        val tradingDays = LinkedHashSet<String>()
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
            if (since == null && tradingDays.size > SESSION_DAYS) break
            if (since != null && items.any { toMinuteCandle(it)?.dateTime?.isBefore(since) == true }) break

            val cursor = parseDateTime(items.last()["xymd"]?.asString(), items.last()["xhms"]?.asString()) ?: break
            keyb = cursor.minusMinutes(1).format(MINUTE_FORMAT)
            next = "1"
        }
        return finish(collected, tradingDays, since)
    }

    fun fetchDailyCandles(excd: String, symb: String): List<OverseasDailyCandle> =
        dailyCandles.get(excd to symb) { fetchDailyPage(excd, symb).mapNotNull(::toDailyCandle) }

    private fun finish(collected: List<JsonNode>, tradingDays: Set<String>, since: LocalDateTime?): List<OverseasMinuteCandle> {
        val candles = collected.mapNotNull(::toMinuteCandle)
        if (since != null) return candles.filter { !it.dateTime.isBefore(since) }
        val keep = tradingDays.sortedDescending().take(SESSION_DAYS).toSet()
        return candles.filter { it.tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE) in keep }
    }

    private fun fetchMinutePage(excd: String, symb: String, next: String, keyb: String): List<JsonNode> {
        val response = client.get(
            "/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice", "HHDFS76950200",
            mapOf(
                "AUTH" to "", "EXCD" to excd, "SYMB" to symb, "NMIN" to "1",
                "PINC" to "1",
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
                "GUBN" to "0",
                "BYMD" to "",
                "MODP" to "1",
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
        const val SESSION_DAYS = 2

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
