# tasks-000: Phase 0 — 부트스트랩

본 문서는 [plan.md Phase 0](./plan.md#phase-0-부트스트랩-현재-상태)의 남은 TODO 작업을 체크 가능한 단위로 분해한 것이다. 완료한 항목은 `[ ]` → `[x]`로 체크하면서 진행.

## 진입 조건 (이미 완료)
- Backend Spring Boot 4 + Kotlin 2.2 + JPA/MySQL 의존성 추가 ✅
- Frontend Vite + React + TS + Tailwind + Router (mock 화면) ✅

## 종료 조건 (Phase 0 DoD)
- `./gradlew bootRun` → 정상 부팅 (DB 미연결 상태에서도 부팅은 되는 상태가 자연스러움. DB 연결은 실제 사용 시점부터)
- `npm run dev` → http://localhost:5173 에서 mock 화면 동작 ✅ 이미 만족
- JSON 형식 로그가 stdout으로 출력 (`{"@timestamp":"...","level":"INFO",...}` 형태)
- 코드 어디서든 `ApplicationCoroutineScope` Bean 주입 가능

---

## 1. 의존성 추가 (`backend/build.gradle.kts`)

- [x] `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.x` 추가 (`ApplicationCoroutineScope` 및 코루틴 사용)
- [x] `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.x` 추가 (`MDCContext`로 코루틴 경계 가로지르기)
- [x] `net.logstash.logback:logstash-logback-encoder:8.x` 추가 (JSON 로그 인코더)
- [x] `./gradlew build` 통과 확인

---

## 2. 설정 파일 작성 (`application.yaml`)

- [x] 기본 server 포트 (8080)
- [x] `spring.jpa.hibernate.ddl-auto: update`, `spring.jpa.open-in-view: false`
- [x] `logging.level.root: INFO`, `logging.level.at.backend: DEBUG`
- [x] DB / KIS / Trading 설정값 — 민감값은 `${ENV_VAR}` 환경변수 참조, 나머지는 기본값 포함

---

## 3. JSON 로깅 (`logback-spring.xml`)

- [x] `backend/src/main/resources/logback-spring.xml` 작성
- [x] `LogstashEncoder` 사용한 ConsoleAppender 정의
- [x] 기본 패턴에 `@timestamp`, `level`, `logger`, `thread`, `message`, MDC 필드 포함
- [x] 부팅 후 stdout에서 JSON 형식 라인 확인 (예: `jq` 파이프로 파싱 가능한지)

> 파일 회전(`logs/trading-YYYY-MM-DD.log`, 90일)은 Phase 7에서 운영 환경 구성 시 추가. Phase 0에서는 stdout JSON 출력만 검증.

---

## 4. MDC 코루틴 컨텍스트 헬퍼 (`common.log`)

- [x] `at.backend.common.log.MdcKey` 객체 — `COMMAND_ID`, `ORDER_ID`, `STOCK_CODE` 키 상수
- [x] `at.backend.common.log.MdcContextElement` 또는 단순 helper — 코루틴 launch 시 `MDCContext()` 사용 가이드 주석 정도
- [x] 단순 사용 예시 단위 테스트 1건 (MDC put → 로그 확인 필요까진 X, MDC 키가 코루틴 경계에서 보존되는지만)

> 본격적인 MDC 주입은 Phase 4 TradingCycle에서 commandId/orderId 자동 주입 시 활용. 여기선 도구만 준비.

---

## 5. @ConfigurationProperties 클래스 (`config/`)

### 5.1 KisProperties
- [ ] `at.backend.config.KisProperties` 작성
- [ ] 필드: `appKey`, `appSecret`, `accountNo`, `accountProductCode`, `baseUrl`, `wsUrl`, `rateLimitPerSecond`
- [ ] `@ConfigurationProperties(prefix = "kis")` + `@ConstructorBinding` 또는 data class
- [ ] `BackendApplication.kt`에 `@ConfigurationPropertiesScan` 또는 명시적 등록

### 5.2 TradingProperties
- [ ] `at.backend.config.TradingProperties` 작성
- [ ] 필드: `marketCloseTime`, `defaultBuyIntervalMin`, `defaultSplitSellRatio`, `defaultMidwayProfitPct`, `defaultBreakevenThresholdPct`, `defaultStopLossPct`, `sellCostRate`
- [ ] `@ConfigurationProperties(prefix = "trading")`

### 5.3 부팅 검증
- [ ] `application-local.yaml`에 채운 값이 두 Properties로 정상 바인딩되는지 (`@SpringBootTest`로 빈 주입 후 단순 not-null assertion)

---

## 6. ApplicationCoroutineScope Bean (`config/CoroutineConfig.kt`)

- [ ] `at.backend.config.CoroutineConfig` 작성
- [ ] `@Bean fun applicationCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("trading"))`
- [ ] `@PreDestroy`로 scope cancel 처리 (서버 종료 시 코루틴 정리)
- [ ] 단위 테스트: 빈 주입 + `launch { ... }` 동작 확인

---

## 7. 검증 (Phase 0 DoD 체크)

- [ ] `./gradlew clean build` 통과
- [ ] `./gradlew bootRun --args='--spring.profiles.active=local'` 정상 부팅
- [ ] stdout 로그가 JSON 형식 (예: `INFO`, `DEBUG` 라인 모두 JSON)
- [ ] `npm run dev` → http://localhost:5173 mock 화면 정상 (이미 만족, 재확인만)
- [ ] `KisProperties` / `TradingProperties` / `ApplicationCoroutineScope` 빈 주입 가능 (간단한 테스트 또는 부팅 로그로 확인)

---

## Phase 0 종료 시점 산출물

```
backend/
├── build.gradle.kts                          # 의존성 3개 추가
├── .gitignore                                # application-local.yaml 무시
└── src/main/
    ├── kotlin/at/backend/
    │   ├── BackendApplication.kt             # @ConfigurationPropertiesScan 추가
    │   ├── common/log/
    │   │   ├── MdcKey.kt
    │   │   └── (MDC 헬퍼)
    │   └── config/
    │       ├── KisProperties.kt
    │       ├── TradingProperties.kt
    │       └── CoroutineConfig.kt
    └── resources/
        ├── application.yaml                  # 공통 설정
        ├── application-local.yaml            # gitignored
        ├── application-local.yaml.example    # 템플릿
        └── logback-spring.xml                # JSON 로깅
```

---

## 다음 Phase

Phase 1 → [tasks-001.md](./tasks-001.md) (도메인 코어 + 단위 테스트)
