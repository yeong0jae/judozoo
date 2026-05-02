package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisAuthResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("access_token_token_expired") val accessTokenExpired: String,
)
