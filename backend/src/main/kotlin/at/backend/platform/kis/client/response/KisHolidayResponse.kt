package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisHolidayResponse(
    val output: List<Output>,
) {
    data class Output(
        @JsonProperty("bzdy_yn") val bzdyYn: String,
    )
}
