package at.backend.platform.kis.client.request

import com.fasterxml.jackson.annotation.JsonProperty

data class KisApprovalRequest(
    @JsonProperty("appkey") val appKey: String,
    @JsonProperty("secretkey") val secretKey: String,
    @JsonProperty("grant_type") val grantType: String = "client_credentials",
)
