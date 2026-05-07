package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisOrderResponse(
    @JsonProperty("rt_cd") val rtCd: String,
    @JsonProperty("msg_cd") val msgCd: String,
    @JsonProperty("msg1") val msg1: String,
    val output: List<Output>? = null,
) {
    data class Output(
        @JsonProperty("odno") val odno: String,
        @JsonProperty("krx_fwdg_ord_orgno") val krxFwdgOrdOrgno: String,
        @JsonProperty("ord_tmd") val ordTmd: String,
    )
}
