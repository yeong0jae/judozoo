# 014 — Phase 12: leadingstock 회귀 안전망

Goal: leadingstock 필터 12개 + FilterChain 결합 로직의 도메인 단위 테스트 보강. 현재 테스트 0건 → Kiwoom·trading 측 변경 시 회귀 가드 확보.

> 도메인 룰만 다룬다. Spring·MockK 없이 Kotest FunSpec(한글 description)로 작성. LeadingStockService/InvestorTrendService 통합 테스트는 별도 phase로 미룸.

---

## 범위

### Phase 1 필터 (단순 — snapshot만)

- [x] `DailyPriceChangeFilter` — 등락률 임계값 경계
- [x] `EtfExclusionFilter` — 운용사 prefix·" ETN" 패턴 분기
- [x] `MarketCapFilter` — 시가총액 임계값 경계 (억원 단위)
- [x] `PriceAboveOpenFilter` — 현재가 ≥ 시가, openingPrice 0 처리
- [x] `TradingValueRankFilter` — 거래대금 순위 임계값 경계

### Phase 2 필터 (Provider 의존)

- [x] `DailyHighPositionFilter` — 60봉 고가 대비 하락률, 빈 캔들 / maxHigh 0 처리
- [x] `MinuteCandleFluctuationFilter` — 1분봉 등락률 절대값, 빈 캔들 / openPrice 0 처리
- [x] `MinuteCandleVolumeFilter` — 1분봉 거래대금 + 평균 대비 증가율 동시 조건
- [x] `OpeningPriceFilter` — 시초가 대비 임계값 / 캔들 부족(<2) 처리
- [x] `PrevDayCloseFilter` — 어제(candles[1]) changeRate 임계값 / 캔들 부족 처리
- [x] `ProgramNetBuyFilter` — 백만원 단위, 음수 임계값 경계
- [x] `ThemeRankFilter` — rank null이면 통과 + 임계값 경계

### 결합 로직

- [x] `FilterChain` — 빈 input / 모두 통과 / 중간에 0 → early break / 순차 누적 차감

---

## 설계 원칙

- **테스트 위치**: `backend/src/test/kotlin/at/backend/leadingstock/application/filter/`
- **클래스명**: 프로덕션 클래스명 + `Test`
- **`context()` / `test()` 한글** — 메서드명·필드명 노출 금지
- **`filter()` boolean에 집중**. `evaluate()`의 문자열 포맷은 행동 변화 아니라 테스트 안 함
- **mock 금지** — provider 람다는 Kotlin lambda 그대로 (`{ stockCode -> listOf(...) }`)
- **`LeadingStockSnapshot`은 fixture 빌더로 생성** — 필수 필드만, 나머지 default

---

## Verification

- [x] `./gradlew test --tests "at.backend.leadingstock.*"` 전체 green
- [ ] 전체 `./gradlew test` 회귀 0건
- [ ] 각 필터 최소 2개 시나리오 (통과·차단), provider 의존 필터는 데이터 부족 케이스도 추가

## DoD

- 12 필터 테스트 + FilterChain 테스트
- 후속 Kiwoom 시세 클라이언트 변경 시 leadingstock 동작 보존 회귀 가드 동작
