package at.backend.platform.kis.client

/**
 * KIS가 200 응답으로 명시적 거부(rt_cd != "0")를 반환한 케이스.
 * msgCd는 거부 원인 코드(예: "EGW00201" 초당 거래건수 초과) — 운영자 추적용.
 */
class KisOrderRejectedException(
    val msgCd: String,
    msg: String,
) : RuntimeException("[$msgCd] $msg")
