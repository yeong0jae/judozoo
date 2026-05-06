package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

// KIS 신TR 응답은 케이스에 따라 필드를 생략한다 (예: KRX-only 라우팅 시 krx_fwdg_ord_orgno, 거부 시 output 전체).
// 모든 필드 nullable로 받고 호출자가 의미 있는 검증을 수행한다.
data class KisOrderResponse(
    @JsonProperty("rt_cd") val rtCd: String? = null,
    @JsonProperty("msg_cd") val msgCd: String? = null,
    @JsonProperty("msg1") val msg1: String? = null,
    val output: Output? = null,
) {
    data class Output(
        @JsonProperty("odno") val odno: String? = null,
        @JsonProperty("krx_fwdg_ord_orgno") val krxFwdgOrdOrgno: String? = null,
        @JsonProperty("ord_tmd") val ordTmd: String? = null,
    )
}
