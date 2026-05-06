package at.backend.platform.kis.client

/**
 * KIS가 200 응답으로 명시적 거부(rt_cd != "0")를 반환한 케이스.
 *
 * 이 예외는 "주문이 KIS에 도달했지만 거부됐다" — 즉 실 체결이 절대 없음을 의미.
 * 호출자는 reconcile 없이 바로 FAILED 처리해도 안전.
 *
 * 반대로 응답 파싱 실패 / HTTP 오류 / 타임아웃은 KIS가 받았는지 처리했는지 모르므로
 * 별도(generic Exception)로 잡아 reconcile로 실 체결 확인이 필요.
 */
class KisOrderRejectedException(
    val msgCd: String,
    msg: String,
) : RuntimeException("[$msgCd] $msg")
