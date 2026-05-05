package at.backend.platform.kis.client.response

import com.fasterxml.jackson.annotation.JsonProperty

data class KisHolidayResponse(
    val output: Output,
) {
    data class Output(
        // 개장일 여부 — 주식시장이 열리는 날 (Y/N).
        // bzdy_yn(영업일)은 금융기관 업무일이라 더 넓은 범주이므로, 주문 가능 판단은 opndYn 사용 (KIS docs 권장).
        @JsonProperty("opnd_yn") val opndYn: String,
    )
}
