package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisDailyCcldResponse(
    val output1: List<Output>,
) {
    data class Output(
        val pdno: String,
        val odno: String,
        @JsonProperty("ord_dt") val ordDt: String,
        @JsonProperty("ord_tmd") val ordTmd: String,
        @JsonProperty("tot_ccld_qty") val totCcldQty: String,
        @JsonProperty("avg_prvs") val avgPrvs: String,
        @JsonProperty("sll_buy_dvsn_cd") val sllBuyDvsnCd: String,
    )
}
