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
 ├─ /api/overseas-leading-stocks/*  →  python:8000   (이관 완료)
 └─ /api/*                          →  backend:8080  (아직 Kotlin)
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
- [ ] `market` 이관 (1,667줄) — 지수 / 선물 / 투자자 수급 / 프로그램매매 / 매크로 / 캘린더
  - [ ] 세션별 수급의 **누적 스냅샷 경계 diff** 계산 로직 보존
- [ ] `theme` 이관 (371줄) + `ThemeCapturePoller` (15:40 / 20:00)
- [ ] `watchlist` 이관 (352줄) — 관심 테마·종목, 표시 순서
- [ ] `issue` 이관 (151줄) — CRUD
- [ ] `FuturesInvestorPoller`(60s), `ProgramTradePoller`(120s) 이관
- [ ] nginx `/api/market/*`, `/api/themes/*`, `/api/watch-themes/*`, `/api/issues/*` 라우팅

**검증**: 시황 분석 / 테마 캘린더 / 이슈 화면 전체 동작

### M5 — leadingstock (도메인 핵심)

가장 크고(3,108줄) 가장 중요하다. 필터는 순수 함수라 이관 난이도 자체는 낮지만, **테스트가 전부 여기 몰려 있다**.

- [ ] 필터 14종 이관 — 순수 함수로 유지, Spring·시간·HTTP 의존 없이
- [ ] `FilterChain` + `FilterEvaluationResult` (탈락 사유 보존)
- [ ] 기존 Kotest 필터 테스트를 pytest로 이관 (`tasks-014` 산출물, 한글 설명 유지)
- [ ] 시그널 이벤트 — 종목 4종 / 시장 4종, **쿨다운 3분** 로직
- [ ] `SignalEventPoller`(10s), `MarketSignalEventPoller`(30s), `IndexReboundPoller`(30s, 09:00~15:30 게이트)
- [ ] `MarketCloseSnapshotCapture` (15:40 평일)
- [ ] nginx `/api/leading-stocks/*` 라우팅

**검증**: 필터 테스트 전건 통과 + 주도주 후보 / 실시간 로그 / 돌파 현황 화면 동작

### M6 — 전환 완료

- [ ] nginx에서 Kotlin 백엔드 라우팅 제거, `/api/*` 전체를 Python으로
- [ ] docker-compose / prod compose에서 Kotlin 서비스 제거
- [ ] GitHub Actions 배포 워크플로우 전환
- [ ] Terraform 변경 (필요 시)
- [ ] `backend/` 디렉터리 처리 — 삭제하지 말고 `backend-kotlin/`으로 두거나 README에 "이전 구현" 명시

**검증**: 6개 화면 전부 Python 백엔드만으로 동작

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
