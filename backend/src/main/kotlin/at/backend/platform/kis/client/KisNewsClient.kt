package at.backend.platform.kis.client

import at.backend.news.domain.StockNews
import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 종합 시황/공시 제목(FHKST01011800) — HTS [0601] 우측 리스트. 종목코드로 관련 뉴스·공시를 최신순으로 받는다.
 * 제목만 주는 API라 원문 링크는 없다. 종목코드 외 파라미터는 전부 공백이 필수.
 * 실패·응답오류 시 빈 리스트(호출측이 빈 목록으로 표시하게).
 */
@Component
class KisNewsClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** [stockCode] 관련 뉴스·공시 제목 목록(최신순). 시장 전체 시황 기사도 해당 종목이 등록돼 있으면 함께 온다. */
    fun fetchStockNews(stockCode: String): List<StockNews> {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/news-title")
                        .queryParam("FID_NEWS_OFER_ENTP_CODE", "")
                        .queryParam("FID_COND_MRKT_CLS_CODE", "")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .queryParam("FID_TITL_CNTT", "")
                        .queryParam("FID_INPUT_DATE_1", "")
                        .queryParam("FID_INPUT_HOUR_1", "")
                        .queryParam("FID_RANK_SORT_CLS_CODE", "")
                        .queryParam("FID_INPUT_SRNO", "")
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHKST01011800")
                .header("custtype", "P")
                .retrieve()
                .body(NewsResponse::class.java)
                ?: return emptyList()
            if (response.rt_cd != "0") {
                log.error("KIS 뉴스 오류: code={}, msg={}", response.msg_cd, response.msg1)
                return emptyList()
            }
            return response.output.orEmpty().mapNotNull { it.toNews() }
        } catch (e: Exception) {
            log.error("KIS 뉴스 조회 실패 (stockCode={})", stockCode, e)
            return emptyList()
        }
    }

    /**
     * 해외(미국) 종목 뉴스 제목 목록(최신순, 10건). 해외뉴스종합(HHPSTH60100C1).
     * SYMB만 넘기면 0건이 온다 — 국가코드·거래소코드를 함께 줘야 종목 필터가 걸린다.
     */
    fun fetchOverseasNews(exchange: String, symbol: String): List<StockNews> {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/overseas-price/v1/quotations/news-title")
                        .queryParam("INFO_GB", "")
                        .queryParam("CLASS_CD", "")
                        .queryParam("NATION_CD", "US")
                        .queryParam("EXCHANGE_CD", exchange)
                        .queryParam("SYMB", symbol)
                        .queryParam("DATA_DT", "")
                        .queryParam("DATA_TM", "")
                        .queryParam("CTS", "")
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "HHPSTH60100C1")
                .header("custtype", "P")
                .retrieve()
                .body(OverseasNewsResponse::class.java)
                ?: return emptyList()
            if (response.rt_cd != "0") {
                log.error("KIS 해외뉴스 오류: code={}, msg={}", response.msg_cd, response.msg1)
                return emptyList()
            }
            return response.outblock1.orEmpty().mapNotNull { it.toNews() }
        } catch (e: Exception) {
            log.error("KIS 해외뉴스 조회 실패 (exchange={}, symbol={})", exchange, symbol, e)
            return emptyList()
        }
    }

    private fun OverseasNewsRow.toNews(): StockNews? {
        val key = news_key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = parseAt(data_dt, data_tm) ?: return null
        return StockNews(
            seqNo = key,
            title = title,
            source = source?.trim().orEmpty(),
            providerCode = "", // 해외 뉴스엔 공시가 섞이지 않는다
            publishedAt = at,
        )
    }

    private fun NewsRow.toNews(): StockNews? {
        val srno = cntt_usiq_srno?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = hts_pbnt_titl_cntt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = parseAt(data_dt, data_tm) ?: return null
        return StockNews(
            seqNo = srno,
            title = title,
            source = dorg?.trim().orEmpty(),
            providerCode = news_ofer_entp_code?.trim().orEmpty(),
            publishedAt = at,
        )
    }

    private fun parseAt(date: String?, time: String?): LocalDateTime? = runCatching {
        LocalDateTime.parse("${date?.trim()}${time?.trim()?.padStart(6, '0')}", dateTimeFmt)
    }.getOrNull()

    // ── 응답 DTO (필요 필드만) ──
    data class NewsResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output: List<NewsRow>? = null,
    )

    data class NewsRow(
        val cntt_usiq_srno: String? = null, // 내용 조회용 일련번호
        val news_ofer_entp_code: String? = null, // 뉴스 제공 업체 코드
        val data_dt: String? = null, // 작성일자 yyyyMMdd
        val data_tm: String? = null, // 작성시간 HHmmss
        val hts_pbnt_titl_cntt: String? = null, // 제목
        val dorg: String? = null, // 자료원(표시용 언론사명 — 공시는 "공시")
    )

    data class OverseasNewsResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val outblock1: List<OverseasNewsRow>? = null,
    )

    data class OverseasNewsRow(
        val news_key: String? = null,
        val data_dt: String? = null, // 조회일자 yyyyMMdd
        val data_tm: String? = null, // 조회시간 HHmmss
        val source: String? = null, // 자료원
        val title: String? = null,
    )
}
