package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisBalanceResponse(
    val output1: List<Holding> = emptyList(),
    val output2: List<Output>,
) {
    data class Output(
        @JsonProperty("prvs_rcdl_excc_amt") val prvsRcdlExccAmt: String,
    )

    data class Holding(
        @JsonProperty("pdno") val pdno: String,
        @JsonProperty("prdt_name") val prdtName: String,
        @JsonProperty("hldg_qty") val hldgQty: String,
        @JsonProperty("pchs_avg_pric") val pchsAvgPric: String,
        @JsonProperty("prpr") val prpr: String,
        @JsonProperty("evlu_pfls_amt") val evluPflsAmt: String,
        @JsonProperty("evlu_pfls_rt") val evluPflsRt: String,
    )
}
