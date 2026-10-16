package judozoo.platform.kiwoom

import judozoo.leadingstock.domain.IndexTick
import judozoo.library.KST
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * 키움 업종 지수 (ka20001). 코드: 001=종합(KOSPI), 101=종합(KOSDAQ).
 *
 * `cur_prc` 앞 부호는 등락 **방향 표식**이다 — 지수 레벨은 음수일 수 없으므로 절댓값으로 읽는다.
 * 등락률은 부호가 의미를 가지므로 그대로 둔다.
 */
@Component
class KiwoomIndex(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 지수 한 시점. */
    data class IndexSnapshot(val industryCode: String, val currentValue: Double, val changeRate: Double)

    /** 현재 지수값/등락률 + 당일 10초 틱(시간 오름차순). */
    data class IndexIntraday(val value: Double, val changeRate: Double, val ticks: List<IndexTick>)

    /** 업종 현재가. 실패 시 null. */
    fun fetchIndex(industryCode: String, marketType: String = "0"): IndexSnapshot? = try {
        log.info("키움 업종 지수 조회 mrkt_tp={} inds_cd={}", marketType, industryCode)
        val body = fetch(industryCode, marketType)
        IndexSnapshot(industryCode, abs(body["cur_prc"]?.asString().toSignedDouble()), body["flu_rt"]?.asString().toSignedDouble())
    } catch (e: Exception) {
        log.error("키움 업종 지수 조회 실패 inds_cd={}", industryCode, e)
        null
    }

    /** 시장별 종합 지수의 당일 인트라데이. */
    fun fetchIndexIntraday(market: Market, on: LocalDate? = null): IndexIntraday? {
        val (industryCode, marketType) = MARKET_CODES.getValue(market)
        return fetchIndexIntraday(industryCode, marketType, on)
    }

    /** 틱 시각은 `tm_n`(HHmmss)에 조회 기준일을 붙여 만든다. */
    fun fetchIndexIntraday(industryCode: String, marketType: String, on: LocalDate? = null): IndexIntraday? {
        val day = on ?: LocalDate.now(clock.withZone(KST))
        return try {
            val body = fetch(industryCode, marketType)
            val ticks = body["inds_cur_prc_tm"]?.values().orEmpty().mapNotNull { toTick(it, day) }.sortedBy { it.at }
            IndexIntraday(abs(body["cur_prc"]?.asString().toSignedDouble()), body["flu_rt"]?.asString().toSignedDouble(), ticks)
        } catch (e: Exception) {
            log.error("키움 업종 인트라데이 조회 실패 inds_cd={}", industryCode, e)
            null
        }
    }

    private fun fetch(industryCode: String, marketType: String): JsonNode =
        client.query(SECT_URL, "ka20001", mapOf("mrkt_tp" to marketType, "inds_cd" to industryCode)).body

    private fun toTick(item: JsonNode, day: LocalDate): IndexTick? {
        val at = parseTime(item["tm_n"]?.asString()) ?: return null
        return IndexTick(
            at = day.atTime(at),
            value = abs(item["cur_prc_n"]?.asString().toSignedDouble()),
            volume = item["trde_qty_n"]?.asString().toSignedDouble().toLong(),
        )
    }

    companion object {
        private const val SECT_URL = "/api/dostk/sect"

        /** 시장 → (업종코드, 시장구분). mrkt_tp 0=코스피, 1=코스닥. */
        private val MARKET_CODES = mapOf(Market.KOSPI to ("001" to "0"), Market.KOSDAQ to ("101" to "1"))

        /** `"143000"`(HHmmss) → 시각. 형식이 어긋나면 null. */
        private fun parseTime(value: String?): LocalTime? {
            val s = value?.trim().orEmpty()
            if (s.length != 6) return null
            return runCatching { LocalTime.of(s.substring(0, 2).toInt(), s.substring(2, 4).toInt(), s.substring(4, 6).toInt()) }.getOrNull()
        }
    }
}
