package judozoo.platform.kiwoom

import tools.jackson.databind.JsonNode

/**
 * 조회 TR 응답 한 쪽. `return_code`가 0이 아니면 [KiwoomApiException]으로 올라와 여기까지 오지 않는다.
 *
 * 연속 조회는 응답 헤더 `cont-yn`이 `Y`면 `next-key`를 실어 다시 부른다.
 */
class KiwoomResponse(
    val body: JsonNode,
    private val contYn: String?,
    val nextKey: String?,
) {
    val hasNext: Boolean get() = contYn == "Y" && !nextKey.isNullOrEmpty()
}
