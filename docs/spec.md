# SPEC: 주도주 매매 판단 보조 시스템 기술 명세

[PRD](./prd.md)의 "무엇을"에 대해 "어떻게"를 정의한다.

---

## 1. 아키텍처

### 1.1 구성

```
Frontend (TypeScript + React + Vite + Tailwind)
  - 6개 조회 화면, TanStack Query 폴링
  - 실시간 푸시 없음 — 화면별 refetchInterval

Backend (Kotlin + Spring Boot 4, package by feature)
  - leadingstock         : 주도주 후보 필터 체인 + 시그널 이벤트 + 지수 시그널
  - overseasleadingstock : 해외(미국) 주도주 랭킹 + 시그널
  - market               : 지수 / 선물 / 투자자 수급 / 프로그램매매 / 매크로 / 캘린더
  - theme                : 테마 일별 스냅샷 + 캘린더
  - watchlist            : 관심 테마·종목
  - issue                : 일자별 이슈 메모 (CRUD)
  - news                 : 종목 뉴스·공시
  - stock                : 종목 마스터 카탈로그 + 검색
  - platform/{kis,kiwoom,toss,yahoo} : 외부 API 어댑터
  - library              : 공유 유틸 (예외 / 시간 / 로깅 / 캐시 / 포맷 / 코루틴)

  계층: presentation → application → domain ← infrastructure
```

### 1.2 설계 원칙

- **Package by feature** — 피처 간 호출은 `application` 계층끼리만. 값 객체 타입 참조는 허용하되 도메인 객체에 메시지를 보내지 않는다
- **도메인에 외부 의존 없음** — `domain/`에서 Spring·HTTP·시간·난수를 직접 쓰지 않고 파라미터로 받는다
- **JPA 엔티티 = 도메인 엔티티** — 별도 도메인 모델 클래스를 두지 않는다
- **환경 분기는 Profile로** — 코드에 `if (broker == ...)` 형태의 분기를 두지 않는다
- **조회 전용** — 쓰기는 사용자가 직접 만드는 데이터(이슈 메모, 관심 테마)와 스냅샷 적재뿐

---

## 2. 기술 스택

**Backend**: Kotlin 2.2 / JVM 21 / Spring Boot 4 (Web MVC) / kotlinx.coroutines / Spring Data JPA + Hibernate / MySQL 8 / Spring `RestClient` / Caffeine (로컬 캐시) / Resilience4j RateLimiter / Logback + logstash-logback-encoder(JSON) / Kotest FunSpec + MockK + Testcontainers + WireMock

**Frontend**: TypeScript + React + Vite / TanStack Query / React Router / Tailwind CSS / React Hook Form + Zod / lightweight-charts (캔들) / d3-hierarchy (테마 트리맵) / motion (전환) / Pretendard·JetBrains Mono

---

## 3. 외부 데이터 소스

한 소스가 모든 데이터를 주지 않는다. 필요한 데이터가 있는 곳에서 가져오고, 어댑터로 격리한다.

| 소스 | 용도 |
|------|------|
| **KIS** | 휴장일, 종목 뉴스·공시, 국내 선물, 해외 지수·차트·랭킹·종목정보 |
| **키움** | 국내 지수, 종목/업종 투자자 수급, 프로그램매매, 테마, 시세 |
| **토스** | 장 운영 캘린더(국내/미국), 투자자별 매매대금 + 캔들 |
| **야후** | 해외 시세 요약 + 캔들 (프리·애프터마켓 포함) |

### 3.1 인증

- KIS·키움: OAuth2 (AppKey/Secret → 액세스 토큰). 토큰은 만료 전 재발급 후 캐시
- 토스: Client Credentials Grant
- 야후: 인증 없음
- 시크릿은 환경변수로 주입. 운영은 GCP Secret Manager

> **키움 IP 화이트리스트** — 키움 API는 outbound IP가 콘솔에 등록돼 있어야 토큰이 발급된다. 키가 맞아도 미등록 IP는 거부된다.

### 3.2 Rate Limit

브로커별 한도를 **공유 리미터 한 버킷**으로 통제한다. 폴러·상세조회·돌파감지가 각자 리미터를 가지면 합산이 한도를 넘는다.

- 키움 조회(`ka*`): 초당 5건 (`kiwoom.query.permits-per-second`로 조정)
- 한도 도달 시 **거부가 아니라 대기** — timeout 20초
- Resilience4j `RateLimiter` + `ClientHttpRequestInterceptor`로 클라이언트 전체에 적용

### 3.3 알려진 응답 함정

실 응답을 다루며 확인된 것들이다. 어댑터 레벨에서 흡수한다.

| 항목 | 내용 |
|------|------|
| 키움 `cur_prc` 부호 | 앞 부호는 등락 방향 표식. 지수·가격 레벨은 `abs()`로 읽어야 한다 |
| 키움 `ka10080` 시각 | 분봉 `cntr_tm`이 HTS보다 1분 이르다. **표시단에서만** +1분 보정 |
| NXT 과거 분봉 | 프리(08:15)·애프터(20:00) 과거 분봉은 `_NX` 코드라야 온다. KRX 기본 코드는 정규장만 |
| NXT 현재가 | SOR 통합(`_AL`) 코드라야 프리·애프터마켓 체결이 반영된다 |
| KIS 해외분봉 `KEYB` | 다음조회 키가 **현지시각**(xymd+xhms) 기준. -1분씩 내려 페이징 |

---

## 4. 데이터 모델 (MySQL)

전부 **스냅샷·이벤트 적재용**이다. 주문·잔고 테이블은 없다.

### 4.1 마스터

| 테이블 | 내용 |
|--------|------|
| `stocks` | 국내 종목 마스터 (KIS 마스터 파일에서 일 1회 갱신) |
| `OverseasStock` | 해외 종목 마스터 (나스닥/뉴욕/아멕스) |

### 4.2 시그널 이벤트

| 엔티티 | 내용 |
|--------|------|
| `SignalEvent` | 종목 전이 — BREAKOUT / BREAKOUT_IMMINENT / VOLUME_SPIKE / MA20_CROSS |
| `MarketSignalEvent` | 시장 전이 — NET_BUY_LEVEL / NET_FLOW_TURN / MA20_REBOUND / MA20_BREAKDOWN |
| `OverseasSignalEvent` | 해외 종목 전이 |
| `MarketFlowStateSnapshot` | 순매수 흐름 상태 (전이 판정의 직전 상태 보관) |

### 4.3 시황 스냅샷

| 엔티티 | 내용 |
|--------|------|
| `MarketInvestorSnapshot` | 시장 투자자 순매수 당일 누적 |
| `MarketCloseSnapshot` | 장 마감(15:40) 확정 스냅샷 |
| `FuturesInvestorSnapshot` | 선물 투자자 순매수 |
| `ProgramTradeSnapshot` | 프로그램매매 (차익/비차익/전체) |
| `IndexMinuteCandleEntity` | 지수 분봉 (20이평 판정용) |
| `OverseasIndexCloseSnapshot` | 해외 지수 종가 |

> **세션별 수급**은 저장하지 않는다. 당일 누적 스냅샷의 **경계 diff**로 계산한다 — 증권사가 세션별 값을 주지 않기 때문.

### 4.4 사용자 데이터

| 엔티티 | 내용 |
|--------|------|
| `DailyIssue` | 일자별 이슈 메모 |
| `WatchTheme` / `WatchThemeStock` | 관심 테마와 구성 종목 (표시 순서 포함) |
| `ThemeDailyRecord` / `ThemeDailyStock` | 테마 일별 등락·거래대금과 구성 종목 |

### 4.5 스키마 관리

Hibernate `ddl-auto=update`. 컬럼 DROP·이름 변경은 `resources/migration/VNNN__*.sql`에 SQL을 두고 각 환경에 수동 적용한다 (`ddl-auto`가 destructive 변경을 하지 않으므로).

> **예약어 주의** — `rank` 같은 MySQL 예약어를 컬럼명으로 쓰면 `ddl-auto`가 조용히 실패한다. `@Column(name = ...)`으로 다른 이름에 매핑한다.

---

## 5. 주도주 후보 선정

### 5.1 필터 체인

`leadingstock.application.filter`의 `StockFilter` 구현 14종을 `FilterChain`이 순차 평가한다.

```
MarketCap → TradingValueRank → DailyPriceChange → DailyHighPosition
→ MinuteCandleVolume → MinuteCandleFluctuation → ProgramNetBuy
→ PrevDayClose → OpeningPrice → PriceAboveOpen → ThemeRank
→ EtfExclusion → SpacExclusion
```

- 각 필터는 `FilterEvaluationResult`로 **통과 여부와 탈락 사유**를 반환한다. 화면에서 "왜 떨어졌는지" 보기 위한 것
- 기준값은 `leading-stock.criteria.*` 설정으로 주입. 도메인 코드에 상수를 박지 않는다
- 필터는 순수 함수 — Spring·시간·HTTP 의존 없음. 그래서 단위 테스트가 전부 도메인 테스트로 닫힌다

### 5.2 시그널 쿨다운

같은 종목·같은 타입은 `leading-stock.signal-event.cooldown-minutes`(기본 3분) 동안 재적재하지 않는다. 프리마켓 출렁임에서 같은 이벤트가 초 단위로 반복 적재되던 문제의 대응이다.

---

## 6. 스케줄러

### 6.1 폴러 (fixedDelay)

| 폴러 | 주기 | 비고 |
|------|------|------|
| `SignalEventPoller` | 10초 | 종목 시그널 전이 |
| `MarketSignalEventPoller` | 30초 | 시장 순매수 단계 전이 |
| `IndexReboundPoller` | 30초 | 지수 5분봉 20이평 — **09:00~15:30에만** 동작 |
| `OverseasSignalEventPoller` | 15초 | 해외 시그널 |
| `FuturesInvestorPoller` | 60초 | 선물 투자자 |
| `ProgramTradePoller` | 120초 | 프로그램매매 (누적은 느리게 변함) |

### 6.2 캡처 (cron, KST)

| 작업 | 시각 | 비고 |
|------|------|------|
| `StockCatalogRefresher` | 08:30 매일 | 종목 마스터 갱신 |
| `MarketCloseSnapshotCapture` | 15:40 평일 | 정규장 마감 확정 수급 |
| `ThemeCapturePoller` | 15:40 / 20:00 매일 | 정규장·애프터마켓 마감 후 2회 |
| `OverseasIndexSnapshotCapture` | 06:10 화~토 | 미국장 마감 후 |

---

## 7. REST API

전부 조회다. 쓰기는 이슈 메모와 관심 테마뿐.

### 7.1 주도주

```
GET /api/leading-stocks/candidates                          # 후보 + 필터 평가 결과
GET /api/leading-stocks/candidates/{code}                    # 상세
GET /api/leading-stocks/candidates/{code}/minute-candles
GET /api/leading-stocks/candidates/{code}/daily-candles
GET /api/leading-stocks/breakout-radar                       # 돌파 근접도
GET /api/leading-stocks/signal-events                        # 종목 시그널 로그
GET /api/leading-stocks/market-signal-events                 # 시장 시그널 로그
GET /api/leading-stocks/market-close-snapshots
GET /api/leading-stocks/market/investor-net-buy
GET /api/leading-stocks/index/{market}/minute-candles
```

### 7.2 해외 주도주

```
GET /api/overseas-leading-stocks/ranking
GET /api/overseas-leading-stocks/index-close-snapshots
GET /api/overseas-leading-stocks/{exchange}/{symbol}
GET /api/overseas-leading-stocks/{exchange}/{symbol}/minute-candles
GET /api/overseas-leading-stocks/{exchange}/{symbol}/daily-candles
```

### 7.3 시황

```
GET /api/market/kospi | /api/market/kosdaq
GET /api/market/{market}/candles
GET /api/market/{market}/investor/daily | /investor/sessions
GET /api/market/{market}/program/daily  | /program/sessions
GET /api/market/futures/{market}/quote | /investor/daily | /investor/sessions | /candles
GET /api/market/futures/night/quote | /candles
GET /api/market/nasdaq/quote | /candles
GET /api/market/macro/quotes | /candles
GET /api/market/calendar/status
```

### 7.4 테마 / 관심 / 이슈 / 종목 / 뉴스

```
GET  /api/themes/calendar
POST /api/themes/capture                       # 수동 캡처 트리거

GET    /api/watch-themes
POST   /api/watch-themes
PATCH  /api/watch-themes/{id} | /order
DELETE /api/watch-themes/{id}
POST   /api/watch-themes/{id}/stocks
DELETE /api/watch-themes/{id}/stocks/{code}
PATCH  /api/watch-themes/{id}/stocks/order
GET    /api/watch-themes/{id}/quotes

GET /api/issues        POST /api/issues
PUT /api/issues/{id}   DELETE /api/issues/{id}

GET /api/stocks/search?q=
GET /api/stocks/{code}/investor/daily
GET /api/news/stock/{code}
```

---

## 8. 프론트엔드 갱신 전략

실시간 푸시 대신 **화면별 폴링 주기**를 둔다.

| 데이터 | `refetchInterval` |
|--------|-------------------|
| 시그널 로그 / 돌파 현황 | 5초 |
| 지수 / 선물 / 수급 / 분봉 | 30초 |
| 프로그램매매 | 120초 |
| 테마 캘린더 | 10분 |
| 과거 일자 조회 | 폴링 안 함 (`false`) |

- `staleTime: 5초`, `refetchIntervalInBackground: true` — 포커스 없는 창도 계속 갱신 (듀얼 모니터 대응)
- 창 복귀 시 재조회 — 탭 전환으로 폴링이 멈춘 사이의 낡은 값을 즉시 최신화

> STOMP/WebSocket을 도입했다가 제거했다. 조회 전용 화면에서 푸시의 이득보다 연결 상태 관리·재연결 시 정합성 비용이 컸다.

---

## 9. 로깅 / 관측

- Logback + `logstash-logback-encoder` JSON 출력
- MDC에 `stockCode` 등 컨텍스트 주입, 코루틴 경계는 `MdcContextElement`로 전파
- `observability/` 디렉터리에 수집 설정

---

## 10. 테스트 전략

`.claude/rules/testing.md`의 규칙을 따른다. Kotest **FunSpec 전용**, `context()` / `test()` 설명은 **한글**로 시나리오와 의미를 쓴다.

| 계층 | 방식 |
|------|------|
| Domain | Spring·MockK 없이 실제 객체로. 필터 14종 경계 조건, `FilterChain` 결합 시나리오 |
| Infrastructure (JPA) | `@DataJpaTest` — 쿼리 메서드·매핑 검증 |
| Infrastructure (외부 API) | WireMock — 성공/실패/타임아웃 시나리오 |
| Application | `IntegrationTestBase` (Testcontainers MySQL). 외부 API만 `@TestConfiguration + @Primary`로 격리 |
| Presentation | MockK로 서비스 모킹 — 컨트롤러 매핑·DTO 검증 |

---

## 11. 배포

- 백엔드·프론트 도커라이즈 (multi-stage). nginx가 `/api`를 `backend:8080`으로 프록시
- `docker-compose.yml` / `docker-compose.prod.yml`
- Terraform — GCP `asia-northeast3` VM + 고정 IP + Artifact Registry + Secret Manager + Workload Identity Federation
- GitHub Actions — WIF 인증 → 빌드·푸시 → scp/ssh 배포 → 헬스체크
- 브로커별 인스턴스는 Profile로 분기 (`kis-real`, `kiwoom-real`)

자세한 내용은 [`infra/docs/plan.md`](../infra/docs/plan.md).

---

## 12. 설정 (`application.yaml`)

```yaml
kis.api:      base-url / app-key / app-secret
kiwoom.api:   base-url / app-key / app-secret
toss.api:     base-url / client-id / client-secret

stock.master:
  kospi-url / kosdaq-url / overseas-urls   # KIS 마스터 파일 CDN

leading-stock:
  criteria:                 # 필터 14종 기준값
    min-market-cap: 3000
    max-trading-value-rank: 35
    min-daily-price-change-rate: 7.0
    ...
  signal-event:
    min-change-rate / poll-interval-millis / cooldown-minutes
  market-signal:
    poll-interval-millis / candle-poll-interval-millis
    session-start: "09:00" / session-end: "15:30"
```
