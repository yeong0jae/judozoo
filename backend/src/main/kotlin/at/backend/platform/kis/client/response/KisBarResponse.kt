package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisBarResponse(
    val output2: List<Output>,
) {
    data class Output(
        @JsonProperty("stck_bsop_date") val stckBsopDate: String,
        @JsonProperty("stck_cntg_hour") val stckCntgHour: String,
        @JsonProperty("stck_oprc") val stckOprc: String,
        @JsonProperty("stck_prpr") val stckPrpr: String,
    )
}
