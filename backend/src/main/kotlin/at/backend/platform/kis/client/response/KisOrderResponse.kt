package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisOrderResponse(
    @JsonProperty("rt_cd") val rtCd: String,
    @JsonProperty("msg_cd") val msgCd: String,
    @JsonProperty("msg1") val msg1: String,
    val output: Output? = null,
) {
    data class Output(
        @JsonProperty("ODNO") val odno: String,
        @JsonProperty("KRX_FWDG_ORD_ORGNO") val krxFwdgOrdOrgno: String,
        @JsonProperty("ORD_TMD") val ordTmd: String,
    )
}
