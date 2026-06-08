package at.backend.market.domain.regime

import java.time.Instant

/**
 * 시장 레짐 한 시점의 관측값 (예측 아님 — "지금 어떻게 흘러가나").
 *
 * - [gap1]: 전일 종가 대비 오전 NXT 갭(%). [gap1Locked]=false면 08:15 전 잠정값.
 * - [gap2]: 08:15 대비 본장 갭(%). 앵커 미고정/본장 전이면 null.
 * - [gap2Coverage]/[gap2Reliable]: 빠진 종목 비율 가드 (낮으면 신뢰낮음).
 */
data class RegimeSnapshot(
    val gap1: Double,
    val gap1Locked: Boolean,
    val gap2: Double?,
    val gap2Coverage: Double?,
    val gap2Reliable: Boolean,
    val asOf: Instant,
)
