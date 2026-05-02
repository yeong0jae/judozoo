package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisBalanceResponse(
    val output2: List<Output>,
) {
    data class Output(
        @JsonProperty("prvs_rcdl_excc_amt") val prvsRcdlExccAmt: String,
    )
}
