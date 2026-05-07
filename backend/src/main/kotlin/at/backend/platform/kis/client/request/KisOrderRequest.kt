package at.backend.platform.kis.client.request

import com.fasterxml.jackson.annotation.JsonProperty

data class KisOrderRequest(
    @JsonProperty("CANO") val cano: String,
    @JsonProperty("ACNT_PRDT_CD") val acntPrdtCd: String,
    @JsonProperty("PDNO") val pdno: String,
    @JsonProperty("ORD_DVSN") val ordDvsn: String,
    @JsonProperty("ORD_QTY") val ordQty: String,
    @JsonProperty("ORD_UNPR") val ordUnpr: String,
    @JsonProperty("EXCG_ID_DVSN_CD") val excgIdDvsnCd: String,
)
