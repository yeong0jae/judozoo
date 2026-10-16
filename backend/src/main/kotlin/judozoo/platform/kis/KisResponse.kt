package judozoo.platform.kis

import tools.jackson.databind.JsonNode

/**
 * KIS 응답 한 번. **HTTP 오류여도 예외로 바꾸지 않는다** — KIS는 한도 초과도 HTTP 500에 본문으로 사유를 실어 준다.
 * 성공 여부와 처리는 엔드포인트가 정한다. 본문이 JSON이 아니면 [body]가 null이다.
 */
class KisResponse(val status: Int, val body: JsonNode?) {
    val msgCd: String? get() = body?.get("msg_cd")?.asString()
    val msg1: String? get() = body?.get("msg1")?.asString()

    /** HTTP 200에 `rt_cd` "0". */
    val ok: Boolean get() = status == 200 && body?.get("rt_cd")?.asString() == "0"

    /** 초당 거래건수 초과. 잠깐 쉬었다 한 번 더 부르면 대개 지나간다. */
    val rateLimited: Boolean get() = msgCd == RATE_LIMITED

    companion object {
        const val RATE_LIMITED = "EGW00201"
    }
}
