package judozoo.platform.kis

import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** KIS 상품기본정보 (CTPF1702R) — 해외 종목 시가총액. */
@Component
class KisOverseasProduct(private val client: KisClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 종목별·미국 현지 날짜별로 하루 보관한다. 조회 실패는 보관하지 않는다. */
    private val marketCaps = TtlCache<Triple<String, String, LocalDate>, Long?>(
        "kisOverseasMarketCap", Duration.ofHours(24), maxSize = 100, skipIf = { it == null },
    )

    /**
     * 시가총액(달러) = 상장주식수 × 현재가. 데이터가 없거나 KIS가 실패하면 null.
     *
     * **실패해도 예외를 올리지 않는다** — 시가총액은 상세 화면의 필터 하나일 뿐이다.
     * 지원하지 않는 거래소는 호출 자체가 잘못된 것이라 예외를 올린다.
     */
    fun fetchMarketCap(excd: String, symb: String): Long? {
        val productType = requireNotNull(PRODUCT_TYPE[excd]) { "지원하지 않는 거래소: $excd" }
        return marketCaps.get(Triple(excd, symb, usDate(clock.instant()))) { load(excd, symb, productType) }
    }

    private fun load(excd: String, symb: String, productType: String): Long? {
        val response = try {
            client.get("/uapi/overseas-price/v1/quotations/search-info", "CTPF1702R", mapOf("PRDT_TYPE_CD" to productType, "PDNO" to symb))
        } catch (e: Exception) {
            log.warn("KIS 상품기본정보 조회 실패 ({}:{})", excd, symb, e)
            return null
        }
        if (!response.ok) {
            log.warn("KIS 상품기본정보 오류: HTTP {} {} {} ({}:{})", response.status, response.msgCd, response.msg1, excd, symb)
            return null
        }
        val output = response.body!!["output"]?.takeIf { it.isObject && !it.isEmpty } ?: return null
        val shares = output["lstg_stck_num"]?.asString()?.trim()?.toLongOrNull()
        val price = output["ovrs_now_pric1"]?.asString()?.trim()?.toDoubleOrNull()
        if (shares == null || price == null || shares <= 0 || price <= 0) return null
        return (shares * price).toLong()
    }

    companion object {
        /** 거래소별 상품유형코드. */
        private val PRODUCT_TYPE = mapOf("NAS" to "512", "NYS" to "513", "AMS" to "529")

        private val US_EASTERN = ZoneId.of("America/New_York")

        /** 해외 시총 캐시는 한국 날짜가 아닌 미국 현지 날짜에 맞춰 교체한다. */
        fun usDate(at: Instant): LocalDate = at.atZone(US_EASTERN).toLocalDate()
    }
}
