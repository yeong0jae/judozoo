# tasks-011 — Phase 9: Broker 추상화

Goal: KIS 직접 호출을 `BrokerTradingClient` 인터페이스 뒤로 숨긴다. 이번 phase에서는 **행동을 1bit도 바꾸지 않는다** — Phase 10에서 Kiwoom 어댑터가 같은 인터페이스로 들어올 수 있는 자리만 만든다.

> 이번 phase의 가치는 "교체 지점이 한 군데로 모인다"는 것 한 줄. 실제 다중-브로커는 Phase 10·11에서 완성.

---

## 설계 원칙

- **행동 보존이 1순위.** 기존 통합 테스트 회귀 0건이 합격 기준.
- **인터페이스는 `trading.application.broker`에 둔다.** Architecture rule: cross-feature 호출은 application 사이만, 의존 방향은 platform → application.
- **어댑터는 얇게.** `KisRestClient`/`KisWebSocketClient` 위에서 단순 위임. 비즈니스 로직이 어댑터에 들어가면 잘못된 추상화 신호.
- **DTO는 trading.domain의 기존 값 객체 재사용.** 어댑터별 DTO를 새로 만들지 않는다.

---

## 사전 작업

- [ ] `feature/broker-abstraction` 브랜치 생성
- [ ] 현재 main의 통합 테스트 전부 green 확인 (회귀 기준점)

## 인터페이스 도출

- [ ] `trading.application.broker.BrokerTradingClient` 정의 — 아래 호출부 6개에서 필요한 메서드 모음
  - 주문 발주 / 취소 / 정정
  - 체결 조회 / 미체결 조회
  - 잔고 조회 (`inquire-balance` 등)
  - 휴장 여부 (`chk-holiday`) — 어느 broker에도 필요
  - 실시간 시세·체결 통지 구독 (`KisWebSocketClient`의 표면) — 인터페이스 모양은 broker-중립
- [ ] 메서드 시그니처를 trading.domain 값 객체 기준으로 작성 (KIS 응답 그대로 노출 ❌)

## KisBrokerAdapter 구현

- [ ] `platform.kis.adapter.KisBrokerAdapter : BrokerTradingClient`
  - 내부에 `KisRestClient`, `KisAuthService`, `KisWebSocketClient`, `KisRateLimiter` 주입
  - 각 메서드는 기존 KIS 호출 위임 + 응답 → domain 값 객체 변환
- [ ] 기존 `platform.kis.*`는 변경 금지 (이번 phase 범위 밖)

## 호출부 교체 (6개 파일)

호출부 식별 결과 (`grep -rln "platform.kis"`):

- [ ] `trading.application.OrderService` — KIS 직접 의존 → `BrokerTradingClient`로
- [ ] `trading.application.TradingService` — 동일
- [ ] `trading.application.TradingQueryService` — 동일
- [ ] `trading.application.TradingValidator` — 동일
- [ ] `trading.application.ExecutionNoticeListener` — WebSocket 통지 수신부, broker-중립 표면으로
- [ ] `trading.application.UnclosedCycleStartupHook` — 동일

각 파일 교체 후 해당 통합 테스트가 green 유지되는지 즉시 확인.

## Verification

- [ ] `grep -rln "platform.kis" backend/src/main/kotlin/at/backend/trading/` 결과 0건
- [ ] `./gradlew test` 전체 green
- [ ] 로컬 `docker compose up` → `vts` profile 부팅, 모든 화면 동작 (수동 smoke)
- [ ] 통합 테스트 9개 (Phase 4 시나리오) 전부 green

## Definition of Done

- 위 Verification 통과
- `BrokerTradingClient`가 외부에 노출하는 표면이 domain 값 객체로만 구성됨 (KIS DTO 누수 0)
- Phase 10에서 `KiwoomBrokerAdapter`만 추가해 `@Profile`로 바꾸면 동작 가능한 구조

## 후속 / Phase 10 (tasks-012)

- `platform.kiwoom.trading.*` 신설 (REST 클라이언트 + 주문/잔고/체결 모듈)
- WireMock 인프라 테스트
- `KiwoomBrokerAdapter : BrokerTradingClient` 구현
- `application-{kis,kiwoom,vts,real}.yml` profile 분리
- broker·env active 검증 fail-fast `ApplicationRunner`
