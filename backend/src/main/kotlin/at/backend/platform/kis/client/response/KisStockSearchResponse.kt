package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisStockSearchResponse(
    @JsonProperty("rt_cd") val rtCd: String,
    val output: Output?,
) {
    data class Output(
        val pdno: String,
        @JsonProperty("prdt_abrv_name") val prdtAbrvName: String,
    )
}
