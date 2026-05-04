package at.backend.platform.kis.client.request

import com.fasterxml.jackson.annotation.JsonProperty

data class KisOrderCancelRequest(
    @JsonProperty("CANO") val cano: String,
    @JsonProperty("ACNT_PRDT_CD") val acntPrdtCd: String,
    @JsonProperty("KRX_FWDG_ORD_ORGNO") val krxFwdgOrdOrgno: String,
    @JsonProperty("ORGN_ODNO") val orgnOdno: String,
    @JsonProperty("ORD_DVSN") val ordDvsn: String,
    @JsonProperty("RVSE_CNCL_DVSN_CD") val rvseCnclDvsnCd: String,
    @JsonProperty("ORD_QTY") val ordQty: String,
    @JsonProperty("ORD_UNPR") val ordUnpr: String,
    @JsonProperty("QTY_ALL_ORD_YN") val qtyAllOrdYn: String,
)
