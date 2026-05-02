# tasks-002: Phase 2 — KIS REST Adapter

This document breaks down [plan.md Phase 2](../plan.md#phase-2-kis-rest-어댑터) into checkable work units.

## Entry conditions

- Phase 1 DoD satisfied (`./gradlew test` all passing, domain coverage 90%+)
- Phase 2 scope: KIS auth + read-only REST endpoints only. Orders (buy/sell/cancel) and WebSocket are handled in Phase 4.

## Exit conditions (Phase 2 DoD)

- WireMock integration tests pass for: normal / 4xx / 5xx / timeout / rate-limit scenarios
- Token expiry-minus-5-min refresh trigger verified
- `kis` package line coverage 70%+

---

## 1. Build dependencies (`backend/build.gradle.kts`)

- [x] Add WireMock — `org.wiremock:wiremock-standalone:3.x` (testImplementation)
- [x] Add Resilience4j — `io.github.resilience4j:resilience4j-ratelimiter:2.x` + `io.github.resilience4j:resilience4j-kotlin:2.x` (implementation)
- [x] Add Testcontainers MySQL — `org.testcontainers:mysql:1.x` + `org.testcontainers:junit-jupiter:1.x` (testImplementation)
- [x] Confirm `./gradlew clean build` passes

---

## 2. Test infrastructure: IntegrationTestBase

- [x] Write `at.backend.common.test.IntegrationTestBase` — `@SpringBootTest` + singleton Testcontainers MySQL container + `@DynamicPropertySource` overriding `spring.datasource.url/username/password`
- [x] Add dummy KIS values in `backend/src/test/resources/application.yaml` so `@SpringBootTest` starts (WireMock will override the base URL per test)

---

## 3. KisProperties package migration

- [x] Move `at.backend.config.KisProperties` → `at.backend.kis.KisProperties` (update all import paths; `BackendApplication.kt` `@ConfigurationPropertiesScan` if needed)
- [x] Verify `./gradlew build` still passes after the move

---

## 4. KisRateLimiter (`at.backend.kis.infrastructure`)

- [x] Implement `KisRateLimiter` — Resilience4j `RateLimiter` bean, limit = `KisProperties.rateLimitPerSecond` per second; `acquire()` blocks until a permit is available
- [x] Unit test (`KisRateLimiterTest.kt`, FunSpec) — calls within limit pass immediately; calls exceeding the limit block then pass after the window resets

---

## 5. KisAuthService (`at.backend.kis.application`)

- [x] Write `KisAuthResponse` DTO — `accessToken: String`, `accessTokenExpired: String` (matches KIS `/oauth2/tokenP` response field names)
- [x] Implement `KisAuthService` — issues token via `POST /oauth2/tokenP` on `@PostConstruct`, stores token + expiry in memory
- [x] Add `refreshIfNearExpiry()` — annotated `@Scheduled(fixedDelay = 60_000)`, re-issues if expiry is within 5 minutes
- [x] Add `getToken(): String` — returns the current valid token (synchronous)
- [x] Integration test (`KisAuthServiceTest.kt`, FunSpec, extending `IntegrationTestBase`) — WireMock stubs for `/oauth2/tokenP`: (1) normal issuance, (2) near-expiry → `refreshIfNearExpiry()` triggers re-issuance, (3) 4xx response → exception propagates

---

## 6. KIS response DTOs (`at.backend.kis.infrastructure.dto`)

- [x] `KisCurrentPriceResponse` — maps `stckPrpr` (current price) from KIS inquire-price response
- [x] `KisHolidayResponse` — maps `bzdyYn` (business-day flag) from KIS chk-holiday response
- [x] `KisStockSearchResponse` — maps `output` array (stock list) from KIS search-stock-info response
- [x] `KisBalanceResponse` — maps `prvsRcdlExccAmt` (orderable cash, T+2 settled) from KIS inquire-balance response
- [x] `KisBarResponse` — maps `output2` array (bar list) from KIS inquire-time-itemchartprice response
- [x] `KisDailyCcldResponse` — maps `output1` array (daily execution list) from KIS inquire-daily-ccld response

---

## 7. KisRestClient (`at.backend.kis.infrastructure`)

- [x] Implement `KisRestClient` — Spring `RestClient` bean with `baseUrl = KisProperties.baseUrl`; every request adds `Authorization: Bearer {token}`, `appkey`, `appsecret` headers; all calls go through `KisRateLimiter.acquire()` first
- [x] `getCurrentPrice(stockCode: String): Int` — `GET /uapi/domestic-stock/v1/quotations/inquire-price`
- [x] `isBusinessDay(date: LocalDate): Boolean` — `GET /uapi/domestic-stock/v1/quotations/chk-holiday`
- [x] `searchStock(keyword: String): List<StockInfo>` — `GET /uapi/domestic-stock/v1/quotations/search-stock-info`
- [x] `getBalance(): Long` — `GET /uapi/domestic-stock/v1/trading/inquire-balance`
- [x] `getBars(stockCode: String): List<Bar>` — `GET /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` (3-minute bars)
- [x] `getDailyExecutions(stockCode: String, date: LocalDate): List<Execution>` — `GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld`

---

## 8. WireMock integration tests (`KisRestClientTest.kt`)

- [x] Add WireMock fixture JSON files under `src/test/resources/wiremock/` — one captured KIS response per endpoint (6 files: current-price, holiday, stock-search, balance, bars, daily-ccld)
- [x] **Normal scenarios** — one test per `KisRestClient` method: 200 response + response parsing assertion (6 tests total)
- [x] **4xx scenario** — invalid stock code → 400 response → domain exception propagates (1 test)
- [x] **5xx scenario** — server error → 500 response → domain exception propagates (1 test)
- [x] **Timeout scenario** — WireMock `fixedDelay(3000ms)` + `RestClient` read-timeout 1s → timeout exception propagates (1 test)
- [x] **Rate-limit scenario** — 21 sequential calls with `KisRateLimiter` at 20 req/s limit → all complete in order, no calls are dropped (1 test)

---

## 9. Verification (Phase 2 DoD check)

- [x] `./gradlew test` all passing
- [x] `./gradlew jacocoTestReport` → `at.backend.kis` line coverage 70%+

---

## Phase 2 end-state artifacts

```
backend/src/main/kotlin/at/backend/kis/
├── KisProperties.kt
├── application/
│   ├── KisAuthService.kt
│   └── dto/
│       └── KisAuthResponse.kt
└── infrastructure/
    ├── KisRateLimiter.kt
    ├── KisRestClient.kt
    └── dto/
        ├── KisCurrentPriceResponse.kt
        ├── KisHolidayResponse.kt
        ├── KisStockSearchResponse.kt
        ├── KisBalanceResponse.kt
        ├── KisBarResponse.kt
        └── KisDailyCcldResponse.kt

backend/src/test/kotlin/at/backend/
├── common/test/IntegrationTestBase.kt
└── kis/
    ├── application/KisAuthServiceTest.kt
    └── infrastructure/KisRestClientTest.kt

backend/src/test/resources/wiremock/
├── current-price-200.json
├── holiday-200.json
├── stock-search-200.json
├── balance-200.json
├── bars-200.json
└── daily-ccld-200.json
```

---

## Next phase

- Phase 3 → tasks-003.md (Persistence + Command API)
