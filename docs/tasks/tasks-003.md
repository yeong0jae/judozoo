# tasks-003 — Phase 3: Persistence + Command API

Goal: Users can submit and cancel trading commands via REST. No background trading yet.

---

## Entities & Schema

- [ ] `CommandEntity` (spec §4.1: all columns — id CHAR(36), stock_code, stock_name, per_buy_amount, buy_interval_min, split_sell_ratio, midway_profit_pct, breakeven_threshold_pct, stop_loss_pct, status, close_reason, breakeven_armed, trend_break_armed, tp_stages_fired, created_at, updated_at, closed_at) + `CommandJpaRepository` (`findByStatusIn`, `findByStockCodeAndStatusIn`, `findByCreatedAtBetween`)
- [ ] `OrderEntity` (spec §4.2: id CHAR(36), command_id FK, side, trigger, order_qty, filled_qty, order_type, kis_order_no, status, retry_count, last_error, created_at, updated_at) + `OrderJpaRepository` (`findByCommandId`)

## Domain

- [ ] `CommandStatus` enum (INITIATED / BUYING / HOLDING / LIQUIDATING / CLOSED) in `command.domain` — used by `CommandEntity` via `@Enumerated(STRING)` and returned in REST responses; `CloseReason` enum reuses `trading.domain.cycle.CloseReason` (stored as String in entity to avoid cross-feature import)

## Application

- [ ] `command.domain.CommandValidator`: constructor-inject `KisRestClient`, `CommandJpaRepository`, `Clock`; validate input ranges first (INVALID_PARAMETER); call KIS searchStock (STOCK_NOT_FOUND); call KIS getCurrentPrice (PRICE_BELOW_ONE_SHARE); call KIS getBalance with active-command reservation deducted (INSUFFICIENT_BALANCE); check duplicate active command (DUPLICATE_COMMAND); check cutoff `15:20 − buyIntervalMin × 2` (CUTOFF_PASSED); call KIS isBusinessDay (HOLIDAY); check trading hours 09:00–15:30 KST (OUT_OF_TRADING_HOURS); throw `CommandValidationException(errorCode: String)`
- [ ] `command.application.CommandService.create()`: call `CommandValidator.validate()` → build `CommandEntity(status=INITIATED)` → `commandRepo.save()` → return `CommandCreatedResponse(commandId, status)`
- [ ] `command.application.CommandService.cancel()`: load by id or throw `EntityNotFoundException`; INITIATED/BUYING/HOLDING → save LIQUIDATING (202 `{status: LIQUIDATING}`); LIQUIDATING → idempotent (202 `{status: LIQUIDATING}`); CLOSED → throw `AlreadyClosedException` (409 ALREADY_CLOSED)

## Presentation

- [ ] DTOs: `CreateCommandRequest` (jakarta.validation — stockCode, perBuyAmount, buyIntervalMin?, splitSellRatio?, midwayProfitPct?, breakevenThresholdPct?, stopLossPct?); `CommandCreatedResponse`; `ActiveCommandSummary`; `CommandDetail` (extends summary + params + orders list); `DailyCommand`; `AccountBalanceResponse {cashBalance, reservedAmount, availableBalance}`; `SystemStatusResponse {marketMode, tokenStatus, isBusinessDay, cutoffPassed}`; `ErrorResponse {errorCode, message}`
- [ ] `CommandController`: `POST /api/commands` → 201 `CommandCreatedResponse`; `DELETE /api/commands/{id}` → 202 / 409
- [ ] `CommandController`: `GET /api/commands?status=active|today` → `ActiveCommandSummary[]` / `DailyCommand[]`; `GET /api/commands/{id}` → `CommandDetail`; `StockController`: `GET /api/stocks/search?query=` → `[{stockCode, stockName}]`; `GET /api/stocks/{code}/price` → `{stockCode, currentPrice, asOf}`; `AccountController`: `GET /api/account/balance`; `SystemController`: `GET /api/system/status` (Phase 3: marketMode=WS, tokenStatus=OK, isBusinessDay via KIS, cutoffPassed via Clock)
- [ ] `GlobalExceptionHandler` (@RestControllerAdvice): `CommandValidationException` → 400 `ErrorResponse(errorCode)`; `AlreadyClosedException` → 409; `EntityNotFoundException` → 404; `MethodArgumentNotValidException` → 400 `INVALID_PARAMETER`

## Tests

- [ ] Unit tests — `CommandValidatorTest`: FunSpec, 8 `context` blocks (one per rejection errorCode); Clock mocked for CUTOFF_PASSED / OUT_OF_TRADING_HOURS; KisRestClient mocked with MockK; no Spring context
- [ ] Integration tests — `CommandServiceTest`: extend `IntegrationTestBase` (Testcontainers MySQL); WireMock for KIS stubs; verify all 8 rejection errorCodes; verify happy-path creates entity with INITIATED status; verify multi-stock simultaneous acceptance deducts reservedAmount cumulatively (second command sees reduced availableBalance)
- [ ] Integration tests — `CommandControllerTest`: MockMvc with mocked `CommandService`; verify DELETE state transitions (save entity as BUYING/HOLDING/LIQUIDATING/CLOSED manually → assert correct response code and body); verify cutoff rejection with mocked Clock; verify `GET /api/commands/{id}` DTO shape matches spec §11.1

## Verification

- [ ] All integration test scenarios pass (Testcontainers MySQL + WireMock KIS)
- [ ] `CommandDetail` / `ActiveCommandSummary` / `DailyCommand` DTOs match spec §11.1 shape — Phase 5 frontend can swap mock → real API without changes

---

## Definition of Done

- 거부 errorCode 8건 + ALREADY_CLOSED 통합 테스트 통과
- 다중 종목 동시 접수 시 잔고 차감 누적 검증
- DELETE 상태별 응답 (BUYING / HOLDING / LIQUIDATING / CLOSED) 검증
- 컷오프 시각 검증 (Clock mock)
- Phase 5 프론트 mock → 실 API 1:1 교체 가능 (DTO 일치)
