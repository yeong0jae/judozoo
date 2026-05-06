package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisOrderResponse(
    @JsonProperty("rt_cd") val rtCd: String,
    @JsonProperty("msg_cd") val msgCd: String,
    @JsonProperty("msg1") val msg1: String,
    val output: Output?,
) {
    // 신TR(TTTC0012U)는 KRX-only 라우팅 등 특정 케이스에서 krx_fwdg_ord_orgno / ord_tmd를 생략한다.
    // odno는 주문 추적의 필수 키라 강제, 나머지는 nullable.
    data class Output(
        @JsonProperty("odno") val odno: String,
        @JsonProperty("krx_fwdg_ord_orgno") val krxFwdgOrdOrgno: String? = null,
        @JsonProperty("ord_tmd") val ordTmd: String? = null,
    )
}
