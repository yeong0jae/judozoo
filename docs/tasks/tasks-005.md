# tasks-005 — Phase 4.5: 매매 사이클 엔진 E2E 검증

Goal: Phase 4에서 구축한 사이클 엔진(`TradingCycleRunner`, `CycleOrchestrator`, `OrderExecutor`, 스케줄러, 시작 hook)을 9개 E2E 시나리오 + 안정성/커버리지 게이트로 검증한다. Phase 5(브로드캐스트) 진입 직전의 실전 합격 라인.

> 기반 구현은 `tasks-004.md`에서 완료. 본 단계는 동작 검증과 발견된 결함 보강.

---

## 사전 정비

- [ ] BUYING 단계 cancel 실시간 반영: `TradingService.cancel`이 `Signal.Cancel`을 큐잉해도 `runBuySequence` 루프가 끝까지 회차 진행하는 문제 해결
  - 옵션 A — runner의 `runBuySequence`에 `signals.tryReceive` 폴링을 추가해 매 회차 사이에 Cancel을 즉시 인지
  - 옵션 B — orchestrator가 `runner.cancel()`(코루틴 취소) + `cycle.close(CANCELLED, now)`를 직접 처리
  - 결정 후 단위/통합 테스트 보강
- [ ] (필요 시) 회차 진행 중 cycle 상태 동기화 — DB의 `LIQUIDATING`이 runner의 in-memory `BUYING` 상태로 덮어써지는 race를 차단

## End-to-end 통합 테스트 (`TradingCycleE2ETest`, IntegrationTestBase)

- [ ] 정상 사이클: 3회 매수 → 봉 종료에서 +2%/+3%/+5% 단계 발동 → 추세 꺾임 잔여 매도 → CLOSED(TAKE_PROFIT)
- [ ] 손절: HOLDING 중 -2% → CLOSED(STOP_LOSS)
- [ ] 본전 매도: +2% 도달 무장 → 매수가 도달 → 전량 매도 → CLOSED(BREAKEVEN); 무장만 된 봉 종료 후 다음 봉 재충족 시 재발동 검증
- [ ] 취소: BUYING 1차 in-flight 중 cancel → in-flight 매수 취소 + 보유분 청산 → CLOSED(CANCELLED)
- [ ] 부분 체결 / NO_FILL: 3회 모두 미체결 → 보유 0 → CLOSED(NO_FILL) 직행
- [ ] WS 끊김 + REST 폴링 fallback: WebSocket 강제 종료 → 폴링으로 시그널 평가 지속 → 재연결 시 WS 복귀
- [ ] 주문 타임아웃 reconcile: 매도 5초 무응답 → 일별 체결 조회 매칭 → 재발사 안 함
- [ ] 다중 종목 동시 운용 (3개): 동일 흐름이 격리되어 동시 진행, 서로의 Mutex/잔고에 영향 없음
- [ ] 15:20 강제 청산: TpStage 분할 익절 후 잔여 보유분 → MarketClose 일제 발행 → CLOSED(MARKET_CLOSE)
- [ ] 시스템 다운 후 재시작 자동 마감: 활성 사이클 있는 상태에서 재기동 → 모두 CLOSED(UNCLOSED) 검증

## Verification

- [ ] `./gradlew test` 전체 통과
- [ ] 코루틴 누수 / Mutex deadlock 없음 (E2E 반복 실행 3회 안정)
- [ ] `at.backend.trading` + `at.backend.market` 라인 커버리지 70%+

---

## Definition of Done

- 위 9개 E2E 시나리오 통과
- 시스템 다운 후 재시작 시 활성 사이클 → UNCLOSED 자동 마감 검증
- Phase 5 진입 가능 상태 (도메인 이벤트 발생점이 STOMP broadcast hook 부착 가능 위치에 정렬됨)
