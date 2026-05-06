package at.backend.library.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// 시스템 전반의 단일 시간대 — 한국 주식 전용 서비스라 모든 LocalDateTime을 KST로 해석.
val KST: ZoneId = ZoneId.of("Asia/Seoul")

// LocalDateTime → Instant. 외부 인터페이스(이벤트 페이로드, STOMP, DB TIMESTAMP WITH TIMEZONE)에 사용.
fun LocalDateTime.toInstantKst(): Instant = atZone(KST).toInstant()

// LocalDate + LocalTime → Instant. BarCache의 봉 종료 시각 산출 등에 사용.
fun LocalDate.atKstInstant(time: LocalTime): Instant = atTime(time).atZone(KST).toInstant()
