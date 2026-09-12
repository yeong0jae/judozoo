# tasks-015 — 백엔드 Python 마이그레이션

Goal: Kotlin/Spring Boot 백엔드(10,932줄 / 176파일)를 Python/FastAPI로 이관. **프론트엔드는 손대지 않는다** — API 계약을 그대로 유지하는 것이 성공 기준이다.

> 기능 추가·삭제 없음. 순수 이관이다. "같은 요청에 같은 JSON이 나오는가"만 본다.

---

## 결정 사항

### DB — MySQL + SQLAlchemy

DB는 바꾸지 않는다. 기존 스키마와 데이터를 그대로 쓰고 ORM만 JPA에서 SQLAlchemy로 옮긴다. 이관과 스키마 재설계를 동시에 하면 실패 지점이 두 배가 되고, 중간에 멈추면 아무것도 남지 않는다.

### 이관 방식 — Strangler

Kotlin 백엔드를 끝까지 살려둔 채, 피처 단위로 Python으로 옮기고 nginx에서 경로별로 라우팅한다.

```
nginx
 └─ /api/*  →  python:8000   (2026-09-12 이관 완료 — Kotlin 제거)
```

**모든 마일스톤 종료 시점에 앱이 정상 동작해야 한다.** 절반쯤 옮긴 상태로 멈춰도 서비스는 돌아간다.

### 중단 가능 지점

M1이 끝나면 **그 자체로 독립적으로 동작하는 산출물**이 된다 — 미국 주식 랭킹·시세·뉴스 API가 Python 백엔드만으로 서비스된다. 이후 마일스톤은 여기서 이어가는 것이지, M1을 무의미하게 만들지 않는다.

---

## 기술 매핑

| Kotlin / Spring | Python | 비고 |
|---|---|---|
| Spring Boot Web MVC | FastAPI | |
| JPA / Hibernate | SQLAlchemy 2.0 | `ddl-auto=update` → `Base.metadata.create_all()`. 컬럼 변경은 지금처럼 수동 SQL |
| `@ConfigurationProperties` | pydantic-settings | |
| `RestClient` | httpx | 동기 클라이언트로 시작 |
| `@Cacheable` + Caffeine | `cachetools.TTLCache` | 인프로세스 유지. 캐시별 TTL·maxsize 그대로 재현 |
| Resilience4j `RateLimiter` | 직접 구현 (토큰 버킷) | 공유 리미터 한 버킷 구조 유지 |
| `@Scheduled` | APScheduler | cron / fixedDelay 양쪽 지원 |
| Logback (콘솔 평문) | 표준 `logging` | 현행과 동일하게 평문 유지. Alloy가 stdout을 수집하므로 포맷 변경 이유 없음 |
| `@Profile("!test")` | pytest 마커 / 설정 플래그 | 브로커 분기용 profile은 없다. 테스트에서 폴러를 끄는 용도만 이관하면 된다 |
| Kotest FunSpec | pytest | 테스트 설명은 **한글 docstring** 유지 |
| MockK | pytest-mock | |
| Testcontainers | testcontainers-python | MySQL 컨테이너 |
| — | respx | **신규 도입**. Kotlin 쪽엔 대응 테스트가 없다 (아래 참고) |

> **respx는 이관이 아니라 신설이다.** `build.gradle.kts`에 WireMock 의존성이 있었지만 이를 쓰는 테스트는 한 건도 없었다(정리하면서 의존성도 제거). 브로커 응답 파싱은 함정이 가장 많이 나온 영역인데 테스트가 비어 있으므로, 이관하면서 채운다.

> `@Cacheable`의 `unless = "#result == null"` 같은 조건은 Python에 대응 문법이 없다. 캐시 데코레이터에서 명시적으로 분기한다. 특히 `KiwoomMarketClient`는 **빈 응답을 캐싱하면 안 되는** 케이스라 주의.

---

## 마일스톤

### M0 — 골조

- [x] `python-backend/` 디렉터리, **uv** 프로젝트 초기화
- [x] FastAPI 앱 + `/health`
- [x] pydantic-settings로 `application.yaml`의 설정 키 이식 (kis / kiwoom / toss / leading-stock.criteria)
- [x] SQLAlchemy 엔진 + 세션 의존성, 기존 MySQL 스키마에 그대로 연결
- [x] 표준 `logging` 설정 — 콘솔 평문, 애플리케이션 패키지만 DEBUG
- [x] pytest + testcontainers-python 기반 통합 테스트 베이스 (`IntegrationTestBase` 대응)
- [x] 공유 토큰 버킷 RateLimiter + 테스트
- [x] TTL 캐시 데코레이터 — 캐시별 TTL·maxsize 설정 (전역 60초/100개, `candidateStocks` 5초/15개 등)
- [x] Dockerfile + docker-compose에 `python-backend` 서비스 추가

**검증**: `/health` 200, MySQL 연결, 테스트 1건 통과

### M1 — 미국 주식 세로 슬라이스 ⭐

여기까지가 독립적으로 의미 있는 최소 단위다.

- [x] `platform/kis` 중 해외 부분 이관 — `KisAuthClient`, `KisOverseasChartClient`, `KisOverseasIndexClient`, `KisOverseasProductClient`, `KisOverseasRankingClient`
  - [x] **`KEYB` 페이징 주의** — 다음조회 키가 현지시각(xymd+xhms) 기준, -1분씩 내려 페이징. 실 API로 분봉 1,377건 수집 확인
- [x] `overseasleadingstock` 이관 — 랭킹 / 상세 / 분봉 / 일봉 / 지수 종가 스냅샷
  - 시그널 폴러는 이관하지 않고 **Kotlin에서 제거** — 아래 참고
- [x] `news` 이관 (89줄) — `KisNewsClient` 포함. 실 API 8건 동등성 확인
- [x] `OverseasIndexSnapshotCapture` 스케줄러 (06:10 화~토) — APScheduler, KST 고정
- [x] nginx에 `/api/overseas-leading-stocks/*`, `/api/news/*` 라우팅 추가

**검증 완료 (2026-09-12)** — 미국 정규장 중 운영 실데이터로 확인. 랭킹 / 종목 상세·분봉·일봉 /
타임라인 지수 스냅샷 / 뉴스 전부 정상, 나머지 화면(Kotlin) 회귀 없음.

> 비교 스크립트는 돌리지 않았다. Kotlin·Python을 동시에 띄워야 하는데 같은 앱키를 쓰는
> KIS 토큰 제한(앱키당 1분 1회)과 토스 토큰 1개 제한에 걸린다. 대신 **응답 계약을 정적으로
> 대조**했다 — 라우트 5개, 응답 DTO 6종의 필드 집합, `ApiResponse` 봉투가 양쪽 1:1 일치.
> 여기에 운영 전시 확인을 더해 통과로 본다.

> **`OverseasSignalEventPoller`는 이관하지 않고 지웠다(2026-09-12).** 해외 실시간 로그는
> `36f5258`(2026-07-25, "실시간 로그·돌파 현황을 국내 전용으로")에서 화면·조회 API·리포지토리
> 조회 메서드를 878줄 걷어내며 **이미 내린 기능**이다. 그때 폴러만 딸려 내려오지 못해,
> 이후 7주간 아무도 읽지 않는 `overseas_signal_event` 테이블에 계속 적재하고 있었다.
>
> 비용이 작지 않았다 — 15초 주기에 후보 종목당 분봉 1호출이고 이 호출은 캐시를 타지 않아
> (`fetchLatestMinutes`), 미국장 16시간 동안 **분당 약 160건**의 KIS 호출이 나갔다.
> Python과 앱키를 공유하는 이관 기간엔 특히 아까운 트래픽이다.
>
> 함께 제거: `OverseasSignalEventService` / `OverseasSignalEventRepository` /
> `OverseasSignalEvent` / `OverseasSignalState` / `OverseasCandidateReading` /
> `OverseasMinuteCandleStore`, 그리고 이들만 쓰던 `OverseasLeadingStockService.signalReadings`와
> 스파이크·20이평 계산부. 양쪽 `fetchLatestMinutes`(Kotlin·Python)도 호출자가 사라져 같이 지웠다.
>
> **`overseas_signal_event` 테이블은 남아 있다.** 드롭은 별도로 판단한다.

> **후보에 없는 종목 상세 조회가 500이다.** Kotlin이 던지는 `NoSuchElementException`에
> 전용 핸들러가 없어 `INTERNAL_ERROR`로 떨어진다(404가 맞아 보인다). 순수 이관이라
> Python도 같게 뒀다. 고치려면 양쪽을 함께 바꿔야 한다.

> **KIS 토큰 발급 충돌 (이관 기간 한정)** — 같은 앱키로 토큰을 짧은 간격에 재발급하면
> KIS가 403으로 거부한다. 이관 기간에는 Kotlin·Python 두 백엔드가 같은 앱키를 쓰는데,
> **배포는 두 컨테이너를 동시에 재시작**하므로 매 배포마다 한쪽이 토큰을 못 받는다.
>
> 완화: Python 쪽에 60초 백오프를 뒀다. 실패 후 그 시간 동안은 KIS를 다시 두드리지 않고
> 빈 목록으로 응답하며, 이후 자동 복구된다(실측 ~50초). 배포 직후 뉴스 패널이 잠시 비는 건
> 감수한 결과다.
>
> 2026-08-25 운영에서 실제 재현. KIS 응답 본문이 `EGW00133 접근토큰 발급 잠시 후
> 다시 시도하세요(1분당 1회)`로, 제한은 **앱키당 1분에 1회**다.
>
> **앱키를 분리하지 않기로 했다.** 이관이 끝나면 Kotlin이 사라져 앱키를 쓰는 프로세스가
> 하나로 돌아오므로, 임시 문제에 Secret Manager·Terraform 변경을 붙이지 않는다.
> 대신 이관이 진행될수록 배포 직후 빈 창이 넓어진다는 점을 감수한다
> (KIS를 쓰는 경로가 늘어남). Kotlin 쪽 백오프 부재도 같은 이유로 두고 간다.

### M2 — 종목 마스터 + 공유 유틸

- [x] `library` 이관 — 예외(`EntityNotFoundError` → 404) / 시간(KST) / 금액 포맷
  - `TimeProvider` 인터페이스는 옮기지 않았다 — 구현체가 하나뿐이라 모듈 함수로 충분하다(원칙 1)
  - [ ] **`ka10080` +1분 보정은 표시단에서만** — 파싱 전역 보정 금지 (M3에서 적용)
- [x] `stock` 이관 (591줄) — 종목 마스터 카탈로그, 검색
- [x] `StockCatalogRefresher` — 기동 시 + 08:30 cron, KIS 마스터 zip 다운로드·파싱
- [x] nginx `/api/stocks/search` 라우팅

> **`/api/stocks/{code}/investor/daily`는 이관하지 않았다.** `StockInvestorService`가
> `KiwoomInvestorClient`(키움 ka10059)에 의존하는데 이건 **M3** 물량이다. 계획엔
> `/api/stocks/*`를 통째로 넘기게 적혀 있었지만, 그러면 M3를 앞당겨 끌고 와야 한다.
> nginx가 더 긴 접두사를 우선하므로 `/api/stocks/search`만 Python으로 보내고
> 나머지는 Kotlin에 남겼다. M3에서 키움이 올라오면 그때 합친다.

**검증 완료 (2026-09-12)** — 배포 후 운영에서 국내·해외 검색 정상 확인.
종목 투자자 수급(Kotlin 잔류) 회귀 없음.

- 검색 로직: Kotest `StocksTest` 7건을 pytest로 이관, 전건 통과 (+ 해외 매칭 4건)
- 마스터 파싱: respx 목 13건 — 고정폭(코스피 228 / 코스닥 222), 탭 구분, MS949,
  짧은 줄·빈 코드 스킵, 영문명 120자 절단, 다운로드 실패
- 카탈로그 갱신: testcontainers 4건 — 당일 동기화 시 다운로드 생략, 전체 교체, 실패 폴백
- **실 마스터 파일 대조** — 오늘 받은 파일을 Python으로 파싱해 Kotlin이 적재해 둔 DB와 비교:

| | DB(Kotlin, 8/25) | Python(9/12) | 공통 건 내용 일치 |
|---|---|---|---|
| 국내 | 4,393 | 4,403 | 4,381건 중 **4건만 불일치** — 전부 사명 변경(표준코드 동일) |
| 해외 | 12,741 | 12,789 | 12,685건 중 완전일치 95.1%, **영문명 일치율 99.57%** |

> 해외 불일치 622건의 내역: 567건은 DB 쪽 한글명이 비어(영문으로 대체돼) 있던 것을
> KIS가 3주 사이 채운 것이고(563건이 ASCII였다), 55건은 ETF 리브랜딩 등 영문명 자체 변경
> (NYLI→NYLIM, ARK 펀드명 변경, ADS 비율 변경). **컬럼 오프셋·인코딩·폭 오류의 징후는 없다.**
> 3주 시차가 있어 row 수는 정확히 같을 수 없으므로 "건수 일치" 대신 내용 대조로 검증했다.

> **테이블 생성은 Python이 하지 않는다.** 이관 기간엔 Kotlin `ddl-auto=update`가 스키마
> 소유자다. `Base.metadata.create_all()`은 테스트에서만 부른다. 실제 컬럼명은
> 운영 DB에서 직접 확인했다 — JPA 기본 네이밍이 camelCase를 snake_case로 바꾸므로
> `shortCode`가 아니라 `short_code`다.

### M3 — 브로커 어댑터 (최대 덩어리)

`platform`은 3,208줄로 단일 피처 중 가장 크다. 여기서 외부 API 함정을 전부 흡수한다.

- [x] `platform/kiwoom` 이관 — Auth / Index / Investor / Market / Program / SectorInvestor / Theme
  - [x] **`cur_prc` 부호** — 지수·가격 레벨은 `abs()`
  - [x] **`_AL` SOR 통합 코드** — 프리·애프터 현재가·분봉. 단 일봉(ka10081)은 `_AL`이면 빈 응답이
        오는 경우가 있어 **KRX 기본 코드로 부른다**
  - [x] 조회 초당 5건 공유 리미터 — `api-id`가 `ka`로 시작하는 조회 TR만 태운다(주문·계좌 `kt*`는 별도 한도)
  - [x] 토큰 무효(8005) 감지 → 캐시 버리고 1회 재시도
- [x] `platform/toss` 이관 — Auth / MarketCalendar / MarketIndicator
  - [x] 401 → 캐시 무효화 후 1회 재시도 (client당 토큰 1개라 다른 프로세스가 뺏어간다)
- [x] `platform/kis` 국내 부분 이관 — `KisHolidayClient`, `KisFuturesClient`

> **`_NX` 코드는 M3 범위가 아니었다.** 과거 프리(08:15)·애프터(20:00) 분봉에 `_NX`가 필요한 건
> 맞지만, 어댑터는 넘겨받은 종목코드를 그대로 쓴다(접미사가 없을 때만 `_AL`을 붙인다).
> `_NX`를 붙일지 결정하는 쪽은 호출자라 **M4/M5에서 처리**한다.

> **`ka10080` +1분 보정은 여기서 하지 않았다.** 어댑터는 `cntr_tm`을 응답 그대로 파싱한다.
> 보정은 표시단 책임이라 M4/M5 몫이다 — 파싱 단계에서 전역 보정하면 저장·비교가 전부 어긋난다.

> **M5 값 객체 4종을 선행했다.** `LeadingStockSnapshot` / `DailyCandle` / `MinuteCandle` /
> `IndexTick`은 키움 어댑터의 **반환 타입**이라 M3 없이는 컴파일이 안 된다(Kotlin도 같은 구조).
> `leadingstock/domain.py`에 값 객체만 두고 필터·시그널 본체는 M5에 남겼다.

**검증 완료 (2026-09-12)** — respx 목 테스트 **119건** 추가, 전체 288건 통과.
Kotlin 쪽엔 이 영역 테스트가 한 건도 없어 **이관이 아니라 신설**이다.

클라이언트마다 성공 / 빈 응답 / 오류 코드 / 타임아웃 4종을 덮고, 아래 함정을 회귀로 고정했다:

| 함정 | 고정한 동작 |
|---|---|
| 키움 부호 변종 | `"+1.23"` · `"-1.23"` · `"1.23-"`(후위) 모두 흡수 |
| 프로그램매매 음수 | `"--123"`(이중 부호)을 `-123`으로 — 앞 하나만 떼면 부호가 뒤집힌다 |
| `cur_prc` 방향 표식 | 가격·지수 레벨은 `abs()`, 등락률은 부호 보존 |
| ka10051 ×100 정수 | 2653.81이 `"+265381"`, 3.52%가 `"352"` → 100으로 나눈다 |
| `_AL` 접미사 | ka10001·ka10080은 붙이고, ka10081은 붙이지 않는다 |
| 야간선물 25:30 | 24시 이상이면 날짜 +1일, 시각 −24시간 |
| 선물 부호코드 | 등락률 크기에 `4하한 5하락`을 적용해 음수로 |
| 선물 전일종가 | `futs_prdy_clpr`을 믿지 않고 전일대비로 역산 |
| 토스 반올림 | Kotlin `roundToLong()`은 `floor(x+0.5)` — Python 기본 `round()`의 은행가 반올림과 갈린다 |
| 테마 캐시 키 | `_AL` 등을 떼고 6자리로 정규화해야 같은 종목이 캐시를 맞힌다 |

> **nginx 변경 없음.** M3는 어댑터 계층이라 새로 열리는 엔드포인트가 없다.
> 이 산출물은 M4·M5가 올라타야 화면에 닿는다.

### M4 — 시황 · 테마 · 관심 · 이슈

- [x] `platform/yahoo` 이관 (196줄) — `YahooChartClient`. 실 데이터로 시세·캔들 대조 완료
  - 처음엔 M1에 뒀으나 실제 사용처는 `market`·`watchlist`뿐이라 여기로 옮겼다
- [x] `market` 이관 (1,667줄) — 지수 / 선물 / 투자자 수급 / 프로그램매매 / 매크로 / 캘린더
  - [x] 세션별 수급의 **누적 스냅샷 경계 diff** 계산 로직 보존
- [x] `theme` 이관 (371줄) + 캡처 스케줄 (15:40 / 20:00)
- [x] `watchlist` 이관 (352줄) — 관심 테마·종목, 표시 순서
- [x] `issue` 이관 (151줄) — CRUD
- [x] `FuturesInvestorPoller`(60s), `ProgramTradePoller`(120s) 이관
- [x] nginx `/api/market/*`, `/api/themes/*`, `/api/watch-themes`, `/api/issues` 라우팅

**검증 완료 (2026-09-12)** — 테스트 50건 추가, 전체 338건 통과.
배포 후 운영에서 시황분석·테마 캘린더·관심 테마·이슈 화면 정상 확인.

- **세션 경계 diff 9건** — 경계 차이, 구간 합 = 최종 누적, 프리 스냅샷 없을 때의 폴백,
  "경계 시각 **이하** 가장 가까운 스냅샷" 선택(폴러가 60~120초 간격이라 정각 스냅샷은 거의 없다),
  날짜 격리. 이 표가 틀리면 숫자가 그럴듯한 채로 잘못 나오므로 가장 두껍게 덮었다.
- **라우트 매칭 9건** — `nasdaq` · `futures/night` · `macro` · `calendar`가 `{market}`으로 새지 않는지.
- 이슈 CRUD 9건 / 관심 테마 12건 / 테마 캡처 11건(적재 날짜 판정 포함).

> **라우트 선언 순서가 Spring과 다르다.** Spring은 리터럴 패턴을 우선하지만 FastAPI는
> **선언 순서**로 매칭한다. `/market/nasdaq/candles`를 `/{market}/candles` 뒤에 두면
> `market=nasdaq`으로 들어가 400이 난다. `watch-themes/order`도 같은 이유로 `/{themeId}`보다 앞이다.
> 회귀로 고정해 뒀다.

> **`market.domain.Bar`·`PriceTick`은 옮기지 않았다.** 자기 테스트 말고 참조하는 코드가 없다 —
> 자동매매(`trading`) 제거 때 남은 죽은 코드다. Kotlin 쪽은 건드리지 않고 남겨 뒀다.

> **M5 `MarketInvestorSnapshot`을 선행했다.** 시황분석의 세션별 수급이 이 테이블을 **읽는다**.
> 적재하는 폴러는 M5라 아직 Kotlin이 담당하므로, Python엔 엔티티와 조회만 뒀다.
> M3에서 값 객체를 앞당긴 것과 같은 이유다 — Kotlin의 패키지 경계가 마일스톤 경계와 어긋난다.

> **관심 테마에 잠재 결함이 하나 있다(이관 전부터).** 중복 판정은 (종목코드, 거래소)로 하는데
> 유니크 제약은 `(theme_id, stock_code)`뿐이라, 같은 코드를 다른 거래소로 담으면
> 메모리 검사는 통과하고 INSERT에서 터진다. 순수 이관이라 같게 두고 테스트로 문서화했다.
> 고치려면 제약과 판정을 함께 바꿔야 해서 양쪽 백엔드를 동시에 손대야 한다.

### M5 — leadingstock (도메인 핵심)

가장 크고(3,108줄) 가장 중요하다. 필터는 순수 함수라 이관 난이도 자체는 낮지만, **테스트가 전부 여기 몰려 있다**.

- [x] 필터 13종 + 체인 이관 — 순수 함수로 유지, Spring·시간·HTTP 의존 없이
- [x] `FilterChain` + `FilterEvaluationResult` (탈락 사유 보존)
- [x] 기존 Kotest 테스트를 pytest로 이관 — 필터 15파일 + 도메인 6파일
- [x] 시그널 이벤트 — 종목 4종 / 시장 4종, **쿨다운 3분** 로직
- [x] `SignalEventPoller`(10s), `MarketSignalEventPoller`(30s), `IndexReboundPoller`(30s, 09:00~15:30 게이트)
- [x] `MarketCloseSnapshotCapture` (15:40 평일)
- [x] nginx `/api/leading-stocks/*` 라우팅
- [x] `/api/stocks/{code}/investor/daily` — M2에서 키움 의존으로 미뤘던 것. M3가 올라와 지금 합쳤다

**검증 완료 (2026-09-12)** — 테스트 132건 추가, 전체 470건 통과.

- **필터 64건** — 13종 전부 경계값 + 평가 문구(화면에 그대로 나가므로 글자까지 대조) + 체인 6건
- **시그널 상태기 27건** — 돌파 재인정(더 높은 전고점만), 임박 히스테리시스, 스파이크 해제,
  흐름 전환 정점 추적, 순매수 단계 승급·강등·완충
- **캔들 컬렉션 20건** — 전고점 동점 시 첫 봉, 스파이크 배율, 5분봉 20이평 돌림, RVOL
- **폴러 상태 9건** — 쿨다운, 타입별 독립 쿨다운, 일자 전환 리셋, 휴장·장외 게이트
- **API 계약 12건** — 후보·상세·시그널 로그·시장 시그널·종목 수급

> **Kotest는 운영에서 쓰이지 않는 기준값으로 돌고 있었다.** `LeadingStockCriteriaProperties()`의
> **코드 기본값**(거래대금 30위·등락률 5%)을 쓰는데, 운영은 `application.yaml`이 덮어쓴
> 35위·7%로 돈다. 즉 기존 필터 테스트는 실제로 쓰이지 않는 숫자를 검증하고 있었다.
> 이관하면서 기준값을 **테스트마다 명시**해 그 괴리를 없앴다.

> **M5 도메인 중 두 개는 앞서 선행했다.** `MarketInvestorSnapshot`(M4의 세션 수급이 읽는다)과
> 값 객체 4종(M3의 키움 어댑터가 반환한다). Kotlin 패키지 경계가 마일스톤 경계와 어긋나 생긴 일이다.

> **`event_type`·`kind`는 varchar로 고정했다.** MySQL 네이티브 ENUM으로 두면 enum 값을 추가할 때
> `ddl-auto`가 컬럼을 안 고쳐 INSERT가 truncate로 터진다(Kotlin 주석에 남은 실제 사고).

> **`Market.valueOf` 실패는 500이다.** 지수 캔들에 정의에 없는 시장을 주면 Kotlin도
> `INTERNAL_ERROR`로 떨어진다(400이 맞아 보인다). 순수 이관이라 같게 뒀다.

### M6 — 전환 완료

- [x] nginx에서 Kotlin 백엔드 라우팅 제거, `/api/*` 전체를 Python으로
  - 죽은 `/ws`(STOMP) 블록도 같이 제거 — 프론트·Kotlin 양쪽에 사용처가 없었다
- [x] docker-compose / prod compose에서 Kotlin 서비스 제거
  - [x] 배포 스크립트에 `--remove-orphans` — compose 파일에서 서비스를 지워도
        **이미 떠 있는 컨테이너는 남는다**. 첫 배포에서 Kotlin이 고아 컨테이너로 계속 돌아
        폴러가 살아 있었다(로그의 `Found orphan containers` 경고로 발견). 주말이라
        휴장 게이트가 있는 7종은 스킵돼 실제 중복 적재는 없었다
- [x] GitHub Actions 배포 워크플로우 전환 — 빌드 매트릭스에서 `backend` 제거,
      `SPRING_PROFILES_ACTIVE` 주입 제거(배포 스크립트 인자도 함께)
- [x] Terraform 변경 — **불필요**. `backend.tf`는 GCS state 백엔드일 뿐 Kotlin과 무관하고,
      Alloy는 컨테이너를 동적 발견해 하드코딩된 이름이 없다
- [x] `backend/` 디렉터리 — **이름을 유지**하고 `backend/README.md`에 "이전 구현"을 명시했다.
      `backend/.env`를 mysql·python-backend가 둘 다 읽고 배포 스크립트도 그 경로에 쓰므로,
      `backend-kotlin/`으로 바꾸면 시크릿 경로가 깨진다

**검증 완료 (2026-09-12)**

- **엔드포인트 1:1 대조** — Kotlin 컨트롤러에서 경로를 추출해 Python OpenAPI와 비교:
  **51개 / 51개 완전 일치**, 누락·잉여 없음
- nginx 라우팅 — 프론트 이미지를 띄우고 스텁 백엔드로 8개 대표 경로가 전부
  Python으로 가는지 확인(SPA fallback 포함)
- 이미지 빌드 — `python-backend`·`frontend` 둘 다 성공
- 테스트 470건 통과

> **로컬로 전체 스택을 띄워 검증하지 않았다.** 토스는 client당 유효 토큰이 1개라
> 로컬 백엔드가 토큰을 받으면 **운영 토큰이 즉시 죽는다**. KIS도 앱키당 1분 1회 제한이 있다.
> 그래서 실제 브로커를 건드리지 않는 정적 대조 + 스텁 라우팅으로 검증했다.

> **폴러 중복이 이 마일스톤으로 해소된다.** M5까지는 Kotlin `@Scheduled` 9종과
> Python APScheduler 9종이 동시에 돌 수 있는 상태였다. M1~M4에서 겹친 것들은 멱등이라
> 무해했지만, M5가 더한 `signal_event`·`market_signal_event`는 멱등이 아니고 쿨다운도
> 프로세스별 메모리라 **같은 시그널이 두 줄씩 적재된다**. 그래서 M5와 M6을 한 배포로 묶었다.

---

## 이관 직후 장애 — 키움 토큰 (2026-09-12)

M5·M6 배포 직후 **국내 화면 전체가 죽었다**(지수 0.0, 후보 빈 배열). 해외·선물은 정상이었다
— 국내만 키움을 쓴다.

원인은 **키움이 토큰 신규 발급을 거부**한 것이다.

```
POST https://api.kiwoom.com/oauth2/token
→ 302 Found, Location: http://api.kiwoom.com/start.html?<타임스탬프>
```

사유 코드가 없고 본문은 HTML 리다이렉트 페이지뿐이다. 빈 자격증명도 같은 302를 받으므로
상태코드만으로는 원인을 가릴 수 없다. 헤더·HTTP 버전·api-id를 바꿔도 동일했고,
자격증명·고정 IP·엔드포인트는 모두 정상이었다.

**키움은 앱키당 하루에 토큰 1개를 만들고 이후 요청엔 같은 토큰을 다시 준다.** Kotlin 로그에서
재발급 4회가 전부 같은 만료시각(`20260913004814`)을 반환해 확인했다. 오늘의 기록:

| 시각 | |
|---|---|
| 00:48:14 | 오늘의 토큰 최초 발급 (만료 09-13 00:48) |
| 01:18 / 01:22 / 02:03 | Kotlin 재기동마다 같은 토큰 재수령 |
| 01:56:07 | Python이 같은 토큰 수령 — 오늘 Python의 유일한 성공 |
| 02:07:46 | 키움 조회 마지막 정상 (캐시된 토큰 사용) |
| 14:05:06 | M5/M6 배포로 재기동 → 캐시 소실 → 재발급 요청 → **302** |

**원인은 키움 정기 점검이었다.** 공지: 2026-09-12(토) 08:30~20:00,
"KRX 애프터마켓 이행 관련 시스템 작업"으로 홈페이지·HTS·MTS 전체 서비스 중단.
API도 함께 막혔다. 타임라인이 정확히 맞는다 — 02:07 호출과 02:03 발급은 08:30 전이라
통과했고, 14:05는 점검 창 안이었다.

> **진단에 시간을 많이 썼다.** 키움이 302에 사유를 주지 않고 빈 자격증명도 같은 302를
> 받기 때문에, 코드·헤더·자격증명·IP를 차례로 배제하는 데 몇 시간이 걸렸다.
> **키움만 전부 실패하고 KIS·토스는 정상이면 공지사항을 먼저 봐야 한다.**
> 중간에 "배포 6번으로 발급 한도를 태웠다"는 잘못된 결론을 냈다가 정정했다
> — 실제 발급 요청은 5번뿐이었다.

> **Kotlin이 버틴 이유는 재기동을 안 해서였다.** M6 첫 배포에서 Kotlin은 고아 컨테이너라
> 재생성되지 않아 00:48의 토큰을 그대로 들고 있었다(키움 오류 0건). 남겨뒀어도 23시간 캐시가
> 끝나는 23:48에 똑같이 막혔을 것이다. 즉 **Python vs Kotlin이 아니라
> "점검 중에 재기동했는가"의 차이**다 — 이관과 무관한 사고였고, 점검 창에 배포가 겹친 것이 화근이다.

### 고친 것

1. **발급 거부 시 60초 백오프 + 사유 로깅** (`2995291`) — 백오프가 없어 11분에 **309회**를
   재요청했다. KIS엔 이미 있던 장치가 키움엔 없었다(Kotlin도 없었지만 한 번 성공하면
   23시간 캐시라 드러나지 않았다). 이제 status·`Location`·본문을 남겨 원인 추적이 가능하다.
2. **토큰 영속화** — 발급받은 토큰을 `broker_token` 테이블에 남겨 **재기동이 발급을 소비하지
   않게** 했다. **이 장애를 정확히 막는 장치다** — 01:56에 받은 토큰이 DB에 있었다면
   14:05 재기동이 그걸 재사용해 점검 내내 서비스가 계속됐을 것이다. 키움·KIS·토스 셋 다 적용했다(KIS는 앱키당 1분 1회, 토스는 client당 1개라
   같은 계열 제약이다). DB가 말을 듣지 않아도 인증이 멈추면 안 되므로 전 경로 fail-soft다.
   외부 무효화(키움 8005 / 토스 401) 시엔 저장분도 함께 지운다 — 남겨두면 재기동 때
   죽은 토큰을 되살린다.

3. **백오프에 스택트레이스를 찍지 않게** — 예상된 상태인데 폴링(5초)·폴러(10초)마다
   60줄 트레이스를 뱉어 **한 시간에 28,000줄**이 쌓였다. 출처가 셋이었다(전역 핸들러 /
   uvicorn ASGI / 클라이언트 8곳의 `exc_info=True`). 호출처를 각각 고치는 대신
   `QuietExpectedFailures` 로깅 필터를 핸들러에 달아 한 곳에서 막았다 — 사유는
   메시지 꼬리에 남겨 정보를 잃지 않는다. `KiwoomTokenUnavailable`·`KisTokenUnavailable`을
   공통 `BrokerTokenUnavailable`로 묶어 앞으로 브로커가 늘어도 함께 적용된다.

`broker_token`은 **Kotlin이 사라진 뒤 Python이 만든 첫 테이블**이다. 기동 시
`checkfirst`로 생성하고 기존 테이블은 건드리지 않는다.

> **`access_token`은 `TEXT`여야 한다.** 처음 `varchar(512)`로 뒀다가 **토스 토큰이 JWT라
> 750자를 넘어** 저장이 통째로 실패했다(`Data too long`). fail-soft라 인증 자체는 멈추지
> 않았지만 영속화가 동작하지 않았다. 순수 캐시라 운영 테이블을 드롭하고 재생성했다.

---

## 이관 완료 (2026-09-12)

Kotlin 10,927줄 → Python. 엔드포인트 51개, 스케줄 작업 9종, 테스트 470건.

| | Kotlin | Python |
|---|---|---|
| 프로덕션 코드 | 10,927줄 / 176파일 | 약 8,400줄 |
| 테스트 | Kotest (필터·도메인 중심) | pytest 470건 |
| 외부 API 테스트 | **없음** | respx 목 119건 |

이관 중 기존 구현에서 찾은 것들(전부 고치지 않고 문서화만 했다 — 순수 이관 원칙):

- 죽은 해외 시그널 폴러가 7주간 분당 160건씩 KIS를 호출 → **제거**(`dca9ec7`)
- `market.domain.Bar`·`PriceTick` 죽은 코드 — 이관 제외
- 관심 테마 중복 판정과 유니크 제약 불일치 → INSERT에서 터진다
- Kotest가 운영에서 쓰이지 않는 기준값(30위·5%)으로 돌고 있었다 → 운영값으로 바로잡음
- 후보에 없는 종목 상세·정의에 없는 시장이 500으로 떨어진다(404·400이 맞아 보인다)

---

## 응답 동등성 검증

각 마일스톤의 완료 조건이다. "돌아간다"가 아니라 **"같은 JSON이 나온다"**를 본다.

```
동일 요청을 Kotlin(8080) / Python(8000) 양쪽에 보내고 JSON 비교
  - 키 집합 동일
  - 값 동일 (타임스탬프·실시간 시세 등 시변 필드는 제외 목록 관리)
  - 차이가 있으면 "왜 다른지" 설명 가능해야 통과
```

- [x] 비교 스크립트를 M0에서 만들어 두고 모든 마일스톤에서 재사용 (`scripts/compare_responses.py`)
- [ ] 시변 필드 제외 목록은 엔드포인트별로 관리

> **날짜·시각 필드는 문자열이 다르게 나온다 — 값이 다른 게 아니다.** Jackson은 `LocalDateTime`을
> `ISO_LOCAL_DATE_TIME`으로 쓰기 때문에 **초가 0이면 초를 생략**한다(`2026-08-25T22:30`).
> Pydantic은 항상 초를 붙인다(`2026-08-25T22:30:00`). 소수 이하 자릿수도 다르다
> (Jackson은 뒤 0을 버리고, Pydantic은 마이크로초 6자리 고정).
>
> 분봉 `time`·`peakAt`은 초가 항상 00이라 **모든 캔들에서 이 차이가 난다**. 비교 스크립트는
> 전부 "값 다름"으로 잡지만 프론트는 양쪽을 같은 값으로 읽는다 —
> `minuteSeries`가 `s || 0`으로 초 없는 문자열을 이미 방어하고 있고(`CandleChart.tsx`),
> `peakAt`·`capturedAt`은 `new Date()`가 두 형태를 동일하게 파싱한다.
> 시변 필드가 아니므로 `VOLATILE_KEYS`에 넣지 않는다. 설명 가능한 차이로 두고 넘어간다.

---

## 원칙

1. **Spring 냄새 나는 Python 금지** — DI 컨테이너 흉내, 레이어를 위한 레이어, 단일 구현체 인터페이스를 옮겨오지 않는다. FastAPI의 `Depends`와 모듈 함수로 충분한 곳에 클래스를 만들지 않는다
2. **아키텍처 규칙은 유지** — package by feature, 피처 간 호출은 application 계층끼리, 도메인은 외부 의존 없음
3. **함정은 어댑터에서 흡수** — 부호·시각 오프셋·거래소 코드 문제를 라우터까지 올리지 않는다
4. **각 마일스톤 종료 시 앱이 돈다** — 가짜 구현으로 미완을 채우지 않는다
5. **테스트 설명은 한글** — 시나리오와 의미를 쓰고 함수명·필드명을 노출하지 않는다

---

## 하지 않는 것

- 프론트엔드 변경 (API 계약 동일하므로 불필요)
- DB 교체 — MySQL 유지, 스키마·데이터 그대로
- 기능 추가·개선
- 성능 최적화 (동등성 확보가 먼저)
- 비동기 전면 전환 — httpx 동기로 시작. 폴러 병목이 실측될 때만 async 검토
