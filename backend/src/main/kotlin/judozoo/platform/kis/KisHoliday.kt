package judozoo.platform.kis

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class KisHoliday(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchOpenDays(baseDate: LocalDate): Set<LocalDate> = try {
        val response = client.get(
            URL, "CTCA0903R",
            mapOf("BASS_DT" to baseDate.format(DateTimeFormatter.BASIC_ISO_DATE), "CTX_AREA_NK" to "", "CTX_AREA_FK" to ""),
        )
        if (!response.ok) {
            log.error("KIS 휴장일 오류 status={} code={} msg={}", response.status, response.msgCd, response.msg1)
            emptySet()
        } else {
            response.body!!["output"]?.values().orEmpty()
                .filter { it["opnd_yn"]?.asString()?.trim() == "Y" }
                .mapNotNull { parseDate(it["bass_dt"]?.asString()) }
                .toSet()
        }
    } catch (e: Exception) {
        log.error("KIS 휴장일 조회 실패 base_dt={}", baseDate, e)
        emptySet()
    }

    companion object {
        private const val URL = "/uapi/domestic-stock/v1/quotations/chk-holiday"

        private fun parseDate(value: String?): LocalDate? {
            val s = value?.trim().orEmpty()
            if (s.length != 8) return null
            return runCatching { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        }
    }
}
