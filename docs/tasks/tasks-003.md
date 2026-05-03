# tasks-003 — Phase 3: 트레이딩 접수 API

Goal: 사용자가 트레이딩을 접수·취소·조회할 수 있는 REST API 구현. 백그라운드 매매는 없음.

> JPA 엔티티(`TradingCycle`, `Order`, `Execution`)와 Repository(`TradingCycleJpaRepository`, `OrderJpaRepository`, `ExecutionJpaRepository`)는 Phase 1 리팩토링에서 완료. 본 Phase는 application/presentation 계층 구현에 집중.

---

## Domain

- [x] `trading.domain.TradingValidator`: `KisRestClient`, `TradingCycleJpaRepository` 주입; 아래 순서로 검증 후 실패 시 `TradingValidationException(errorCode)` 발생
  1. 입력값 범위 검사 (INVALID_PARAMETER)
  2. `searchStock` — 종목 존재 여부 (STOCK_NOT_FOUND)
  3. `getCurrentPrice` — 1주 가격 ≤ perBuyAmount (PRICE_BELOW_ONE_SHARE)
  4. `getBalance` — INITIATED 사이클 예약금 차감 후 잔고 (INSUFFICIENT_BALANCE)
  5. 동일 종목 활성 사이클 중복 (DUPLICATE_COMMAND)
  6. 컷오프 `15:20 − buyIntervalMin × 2` 초과 (CUTOFF_PASSED)
  7. `checkHoliday` — 영업일 여부 (HOLIDAY)
  8. 거래 시간 09:00–15:30 KST (OUT_OF_TRADING_HOURS)

## Application

- [x] `trading.application.TradingService.create()`: `TradingValidator.validate()` → `TradingCycle(status=INITIATED)` 생성 → `TradingCycleJpaRepository.save()` → `TradingCreatedResult` 반환
- [x] `trading.application.TradingService.cancel()`: id로 `TradingCycle` 조회 (없으면 404); INITIATED/BUYING/HOLDING → LIQUIDATING 저장 (202); LIQUIDATING → 멱등 (202); CLOSED → `AlreadyClosedException` (409)

## Presentation

- [x] Request/Result DTOs: `CreateTradingRequest` (jakarta.validation), `TradingCreatedResult`, `TradingSummaryResult`, `TradingDetailResult` (`activeSell=null`), `DailyTradingResult`, `TradingCancelResult`
- [x] `TradingController`: `POST /api/trading` → 201; `DELETE /api/trading/{id}` → 202 / 409; `GET /api/trading?status=active|today`; `GET /api/trading/{id}`
- [x] `StockController`: `GET /api/stocks/search?query=`; `GET /api/stocks/{code}/price`
- [x] `AccountController`: `GET /api/account/balance`
- [x] `SystemController`: `GET /api/system/status` (Phase 3: isBusinessDay via KIS, cutoffPassed via Clock, marketMode=WS, tokenStatus=OK)
- [x] `GlobalExceptionHandler` (@RestControllerAdvice): `TradingValidationException` → 400; `AlreadyClosedException` → 409; `EntityNotFoundException` → 404; `MethodArgumentNotValidException` → 400 INVALID_PARAMETER

## Tests

- [x] 단위 테스트 — `TradingValidatorTest`: FunSpec; 8개 거부 errorCode 각각 context 블록; KisRestClient MockK; Spring 컨텍스트 없음
- [x] 통합 테스트 — `TradingServiceTest`: `IntegrationTestBase` (Testcontainers MySQL); WireMock KIS 스텁; 8개 거부 검증; 정상 접수 후 DB에 `TradingCycle(status=INITIATED)` 저장 확인; 다중 종목 동시 접수 시 잔고 차감 누적
- [x] 통합 테스트 — `TradingControllerTest`: MockMvc + mocked `TradingService`; DELETE 상태별 응답 (BUYING/HOLDING/LIQUIDATING/CLOSED); `GET /api/trading/{id}` DTO shape 검증

## Verification

- [x] 거부 errorCode 8건 + ALREADY_CLOSED 통합 테스트 통과
- [x] 다중 종목 동시 접수 시 잔고 차감 누적 검증
- [x] DELETE 상태별 응답 검증
- [x] `TradingDetail` DTO shape이 spec §11.1과 일치 — Phase 5 프론트 mock → 실 API 1:1 교체 가능

---

## Definition of Done

- 위 통합 테스트 시나리오 전체 통과
- Phase 5 프론트 통합 시 mock → 실 API 1:1 교체 가능 (DTO 일치)
