# SPEC: judozoo 기술 명세

[PRD](./prd.md)의 "무엇을"에 대해 "어떻게"를 정의한다.

---

## 1. 아키텍처

### 1.1 구성

```
Frontend (TypeScript + React + Vite + Tailwind)
  - 조회 화면 7개 + 약관 2개, TanStack Query 폴링
  - 실시간 푸시 없음 — 화면별 refetchInterval

Backend (Python + FastAPI, package by feature)
  - leadingstock         : 국내 후보 풀·필터 체인·첫 화면 주도주·눌림/돌파·종목/시장 시그널
  - overseasleadingstock : 미국 3개 거래소 후보 풀·주도주·종목 상세
  - leadertimeline       : 첫 화면 주도주 1분 스냅샷
  - leadercalendar       : 첫 화면 주도주 마감 스냅샷
  - market               : 지수·선물·매크로·투자자 수급·휴장 캘린더
  - stock                : 종목 마스터 카탈로그·검색·종목 수급
  - auth                 : 구글 OAuth·세션·공개 허용목록
  - feedback             : 의견 접수
  - platform/{kis,kiwoom,toss,yahoo} : 외부 API 어댑터
  - library              : 캐시·DB·레이트리미터·토큰 저장소·스케줄러·로깅·메트릭·트레이스

  계층: presentation → application → domain ← infrastructure
  계층은 디렉터리가 아니라 모듈 파일(application.py 등)이다
```

### 1.2 설계 원칙

- **Package by feature** — 피처 간 호출은 `application` 계층끼리만. 값 객체 타입 참조는 허용하되 도메인 객체에 메시지를 보내지 않는다 (`.claude/rules/architecture.md`)
- **도메인에 외부 의존 없음** — `domain`에서 FastAPI·HTTP·시간·난수를 직접 쓰지 않고 파라미터로 받는다
- **SQLAlchemy 모델 = 도메인 엔티티** — 별도 도메인 모델 클래스를 두지 않는다
- **주도주의 정의는 한 곳** — `LeadingStocks.leaders` / `OverseasStockRanks.leaders`. 타임라인·캘린더는 그 답을 기록할 뿐 기준을 새로 만들지 않는다
- **브로커 분기 없음** — 4개 소스를 한 인스턴스가 모두 호출한다. `if broker == ...` 분기를 두지 않는다
- **조회 전용** — 쓰기는 스냅샷·이벤트 적재, 가입자 기록, 의견 접수뿐. 사용자가 공용 데이터를 바꾸는 경로는 없다

---

## 2. 기술 스택

**Backend**: Python 3.13 / FastAPI / SQLAlchemy 2.0 + PyMySQL / MySQL 8 (Cloud SQL) / `httpx` / `cachetools` / APScheduler / authlib / prometheus-fastapi-instrumentator / OpenTelemetry / pytest + respx + Testcontainers / uv

**Frontend**: TypeScript + React 18 + Vite / TanStack Query / React Router / Tailwind CSS 4 / React Hook Form + Zod / lightweight-charts / motion / NumberFlow / Pretendard·Outfit·JetBrains Mono

---

## 3. 외부 데이터 소스

한 소스가 모든 데이터를 주지 않는다. 필요한 데이터가 있는 곳에서 가져오고, `platform/<vendor>/`로 격리한다.

| 소스 | 용도 |
|------|------|
| **키움** | 국내 거래대금 상위·종목 시세·지난 날 분봉·일봉, 프로그램 순매수, 지수, 업종·종목 투자자 수급 |
| **KIS** | 국내 휴장일, 국내 선물(주간·야간), 해외 거래대금 순위·종목정보·차트, 종목 마스터 파일 |
| **토스** | 국내·미국 장 운영 캘린더, 시장 지표(투자자별 매매대금·캔들), 국내 종목 **당일** 1분봉 |
| **야후** | 나스닥 지수·나스닥 선물·매크로(원달러, WTI, VIX, 미 10년물) 시세와 캔들 |

### 3.1 인증

- KIS·키움: AppKey/Secret → 액세스 토큰. 토스: Client Credentials. 야후: 없음
- **토큰은 DB(`broker_token`)에 영속한다.** 발급이 희소 자원이라서다 — 키움은 앱키당 하루 1개, KIS는 1분 1회, 토스는 client당 1개만 유효하다. 메모리에만 두면 재기동마다 발급을 소비하고, 2026-09-12 토요일 재기동에 키움이 발급을 거부해 국내 화면 전체가 죽었다
- 토큰 저장소는 fail-soft — DB가 안 되면 경고만 남기고 메모리 전용으로 동작한다
- 발급 백오프 중인 요청은 `BrokerTokenUnavailable` → 500, 스택트레이스 없이 한 줄만 남긴다
- 시크릿은 환경변수로 주입한다. 운영은 GCP Secret Manager에서 배포 때 받는다

> **키움 IP 허용목록** — 키움은 outbound IP가 콘솔에 등록돼 있어야 토큰을 준다. 그래서 앱 VM은 고정 외부 IP를 유지한다.

### 3.2 Rate Limit

브로커별 한도를 **공유 토큰 버킷 하나**로 통제한다 (`library/rate_limiter.py`). 폴러·상세조회·화면 요청이 각자 리미터를 가지면 합산이 한도를 넘는다.

- 브로커 한도: 키움 초당 5건(**09:00~10:00은 3건**), KIS 초당 18건, 토스는 API 그룹별 — 종목 캔들 `MARKET_DATA_CHART` 20건, 지수 캔들 `MARKET_INDICATOR_CHART` 5건, 장 운영 캘린더 `MARKET_INFO` 3건, 투자자별 매매대금 `MARKET_INDICATOR` 10건(안 씀), `MARKET_DATA` 15건(해당 API 안 씀)
- 키움 조회: 초당 5건, 09:00~10:00 초당 3건 (`KIWOOM_QUERY_PERMITS_PER_SECOND`, `KIWOOM_PEAK_QUERY_PERMITS_PER_SECOND`). 피크타임은 장 시작 직후라 분봉 캐시를 처음 채우는 때와 겹친다
- KIS 조회: 초당 15건 (`KIS_QUERY_PERMITS_PER_SECOND`). 한도 18건에서 3건 여유
- 토스 종목 캔들: 초당 16건 (`TOSS_CHART_PERMITS_PER_SECOND`). 한도 20건에서 4건 여유
- 토스 지수 캔들: 초당 4건 (`TOSS_INDICATOR_CHART_PERMITS_PER_SECOND`). 1분봉 차트 한 번이 200봉 페이지를 최대 6번 연달아 받아, 리미터 없이는 요청 하나가 한도를 넘는다. 결과는 30초 캐시
- 토스 장 운영 캘린더: 리미터 없음. 지역별 하루 한 번이고, 몰려도 한 번만 묻는다(잠금). 실패한 폴백은 5분 뒤 다시 묻는다
- 1초에 몰아 충전하지 않고 균등 발급한다
- 한도에 닿으면 **거부가 아니라 대기**, timeout 키움 20초 · KIS 30초

### 3.3 캐시

`library/cache.py`의 `@ttl_cache` — 인프로세스 TTL 캐시. **방문자 수와 브로커 호출 수를 분리하는 장치**다.

- **single-flight** — 만료 순간 동시에 미스난 요청은 브로커를 한 번만 부르고 결과를 나눠 받는다
- **`skip_if`** — 빈 응답은 캐싱하지 않는다. 키움 거래대금 상위가 빈 리스트로 캐싱되면 TTL 동안 빈 화면이 된다
- **`fn.refresh()`** — 폴러가 만료 전에 미리 갈아 끼운다. 실패하면 기존 값을 건드리지 않는다
- **가변 TTL** — 후보 풀은 장중 15초, 장이 멈춘 동안은 다음 장 시작까지 들고 있는다

### 3.4 알려진 응답 함정

실 응답을 다루며 확인된 것들이다. 어댑터에서 흡수한다.

| 항목 | 내용 |
|------|------|
| 키움 `cur_prc` 부호 | 앞 부호는 등락 방향 표식. 가격 레벨은 `abs()`로 읽는다 |
| 키움 `ka10080` 시각 | 분봉 `cntr_tm`이 HTS보다 1분 이르다. **표시단에서만** +1분 보정 |
| NXT 과거 분봉 | 프리(08:00)·애프터(20:00) 과거 분봉은 `_NX` 코드라야 온다. KRX 기본 코드는 정규장만 |
| NXT 현재가 | SOR 통합(`_AL`) 코드라야 프리·애프터마켓 체결이 반영된다 |
| 토스 캔들 시각·빈 봉 | 시각이 봉 **끝**이라 1분 당겨 키움(봉 시작)과 맞춘다. 체결 없는 분도 거래량 0 봉으로 채워 오므로 버린다. 맞추면 키움 ka10080과 같은 봉이다(2026-09-23 실측 692봉 일치) |
| 토스 심볼 | `_`를 못 쓴다. 랭킹 코드의 `_AL`을 떼고 부른다 |
| KIS 해외분봉 `KEYB` | 다음조회 키가 **현지시각** 기준. -1분씩 내려 페이징 |
| KIS 해외분봉 이어 받기 | 한 번에 120봉이라 2거래일이 12페이지 안팎이다. 종목별로 들고 있다가 새 봉만 받는다(`overseasleadingstock/minutes.py`). 날은 **현지 영업일(tymd)**로 가른다. 한도 초과는 HTTP 500 + `EGW00201`로 온다 — 1초 쉬고 한 번 더 부르고, 뒷페이지가 끊기면 받은 데까지만 보여 준다. 끝난 날은 `overseas_minute_candle`에 넘겨, 처음 열 때 보관한 날 뒤 봉만 받는다. 같은 종목을 동시에 받으려는 화면 요청은 하나로 합친다(`library/singleflight.py`, 국내 당일 분봉도 같다) |
| KIS 해외 순위 | ETF 구분 필드가 없다. 발행사 브랜드 키워드로 판별한다 |

---

## 4. 데이터 모델 (MySQL)

외래키는 걸지 않는다(코드베이스 관례). 부모를 지울 때 자식도 같은 트랜잭션에서 지운다.

### 4.1 마스터

| 테이블 | 내용 |
|--------|------|
| `stocks` | 국내 종목 마스터 — KIS 마스터 파일에서 매일 갱신, 검색은 메모리 사본으로 |
| `overseas_stocks` | 해외 종목 마스터 (나스닥·뉴욕·아멕스) |

### 4.2 시그널 이벤트

| 테이블 | 내용 |
|--------|------|
| `signal_event` | 종목 전이 — `VOLUME_SPIKE`. 돌파·임박은 2026-09-13, 반등·꺾임(`MA_REBOUND` / `MA_BREAKDOWN`)은 2026-09-28 제거, 옛 행은 이력 |
| `market_signal_event` | 시장 전이 — `NET_BUY_LEVEL` / `NET_FLOW_TURN` / `MA_REBOUND` / `MA_BREAKDOWN` |
| `market_flow_state` | (시장, 투자자)별 순매수 흐름 정점. 재시작 후 흐름 전환 판정을 이어가기 위한 것 |

### 4.3 시황 스냅샷

| 테이블 | 내용 |
|--------|------|
| `market_investor_snapshot` | 시장 투자자 순매수 당일 누적 |
| `futures_investor_snapshot` | 선물 투자자 순매수 당일 누적 |
| `index_minute_candle` | 지수 1분봉 — 5분봉 20이평 판정용 |
| `stock_minute_candle` | 지난 거래일 종목 1분봉 — **완성된 날만**, 하루치를 통째로 갈아 끼운다. 지난 날 분봉 보관소의 영속 층(메모리 앞, DB 뒤). 수정주가 소급이 반영되지 않아 2주만 두고 매일 20:01에 지운다 |
| `overseas_minute_candle` | 지난 거래일 해외 종목 1분봉 — `stock_minute_candle`과 같은 방식. 날은 미국 현지 영업일, 시각은 한국 시각. 2주만 두고 뉴욕 20:01에 지운다 |
| `wide_limit_day` | 그날 +30%를 넘은 적이 있는 종목(상장 첫날). 넘은 뒤 +30% 아래로 내려와도 그날 내내 순위용 등락률을 제한폭 대비로 환산하고(÷10) 순위용 거래대금을 반으로 본다 — 재시작해도 잃지 않게 DB에 둔다. 일주일 지나면 지운다 |

> **세션별 수급은 저장하지 않는다.** 당일 누적 스냅샷의 **경계 diff**로 계산한다. 폴러가 돌지 않은 과거는 소급할 수 없다.

### 4.4 주도주 기록

| 테이블 | 내용 |
|--------|------|
| `leader_day` / `leader_day_stock` | 캘린더 — (지역, 현지 날짜)별 마감 주도주. `closed`면 휴장 |
| `leader_tick` / `leader_tick_stock` | 타임라인 — (지역, 현지 분)별 주도주 |

날과 종목(분과 종목)을 나눈 이유 — 주도주가 0개인 날·분이 있다. 부모 행만 있으면 "주도주 없음", 부모 행이 없으면 "기록 없음"이다. 해외 행의 날짜·시각은 **뉴욕 현지 기준**이다.

### 4.5 사용자·운영

| 테이블 | 내용 |
|--------|------|
| `app_user` | 가입자 — 대체 키 `id`, `google_sub`(UNIQUE), `email`, `created_at`, `last_login_at` |
| `feedback` | 의견 — `user_id`, `content`, 작성 시각. 이메일은 `app_user`에만 둔다 |
| `broker_token` | 브로커별 최신 액세스 토큰 한 줄 |

- `app_user`는 **가입자 수를 아는 유일한 지표**다. 키가 `google_sub`인 것은 이메일이 바뀔 수 있어서다
- `last_login_at`은 OAuth 콜백을 통과한 시각이다. 세션 쿠키가 살아 있는 동안의 재방문은 갱신하지 않는다 — **"마지막 접속"이 아니다**
- 사용자별 데이터(이슈 메모·관심 테마)는 017에서 기능째 제거했고 테이블은 `V005`에서 지웠다

### 4.6 스키마 관리

- 017 이후 추가된 테이블(`app_user`, `feedback`, `broker_token`, `leader_*`, `stock_minute_candle`, `overseas_minute_candle`, `wide_limit_day`)은 기동 시 `create(checkfirst=True)`로 만든다. 실패해도 기동은 막지 않는다 — 그 기능만 실패하고 공개 화면은 뜬다
- 그 밖의 테이블은 이미 존재하고 앱이 DDL을 만들지 않는다
- 컬럼 변경·DROP은 `backend/migration/VNNN__*.sql`로 두고 각 환경에 수동 적용한다 (현재 `V008`까지)

---

## 5. 주도주 선정

### 5.1 후보 풀

키움 거래대금 상위를 **서버가 10초마다 받아 캐시에 갈아 끼운다**(08:00~20:00 KST). 후보 목록·첫 화면 주도주·상한가·눌림/돌파·시그널·타임라인·캘린더가 전부 이 풀 하나를 나눠 쓴다. 따로 받으면 호출 사이에 풀이 바뀌어 화면끼리 다른 순간을 말하고, 키움 한도에도 걸린다.

해외는 KIS 거래대금 순위를 거래소 3곳(NAS·NYS·AMS)에서 받아 합친다. 같은 10초 주기, 04:00~20:00 뉴욕.

### 5.2 필터 체인

`leadingstock/filters.py`의 `StockFilter` 구현을 `FilterChain`으로 묶는다. 각 필터는 `FilterEvaluationResult`로 **통과 여부와 실제 값**을 돌려준다.

| 용도 | 필터 |
|------|------|
| 후보 목록 (`find_candidate_stocks`) | ETF/ETN 제외 → 스팩 제외 → 거래대금순위 → 당일등락률 |
| 종목 상세 (`evaluate_stock`) | 거래대금순위 · 당일등락률 · 최근 고가 대비 · 시가 대비 · 전일 등락률 · 시초가 · 시가총액 · 프로그램 양매수 |

- 상세의 나열 순서가 곧 화면 표시 순서다. 판별력이 큰 것부터 둔다
- 기준값은 `LeadingStockCriteria`(`LEADING_STOCK_CRITERIA_*`)로 주입한다. 도메인에 상수를 박지 않는다
- 상세의 일봉 필터 셋은 일봉 1회 조회를 공유한다
- `MinuteCandleVolumeFilter`·`MinuteCandleFluctuationFilter`는 정의만 있고 어느 체인에도 들어가 있지 않다

### 5.3 첫 화면 주도주와 상한가

- `LeadingStocks.leaders(5)` — 등락률 0% 이상 후보를 **거래대금·등락률 두 축 점수**로 다시 세운다 (`library/ranking.top_balanced`)
- `LeadingStocks.limit_ups()` — 주도주 5개가 아니라 **후보 풀 전체**에서 찾는다. 상한가는 잠기면서 거래가 말라 거래대금 점수가 낮아지기 때문이다
- `/leaders`는 두 결과를 한 응답에 싣는다. 같은 순간의 풀에서 나왔다는 것을 보장하기 위해서다

### 5.4 눌림·돌파

최근 3거래일 분봉에서 고가(돌파선)와 저가(눌림선)를 잡고 현재가와의 갭을 잰다. `mode=resistance|support`로 기준선을 고르고, 선에서 `RADAR_NEAR_RATE`(3%) 이내만 가까운 순으로 담는다. 이 값은 프론트 `BreakoutRadarPage`의 `NEAR`와 같아야 한다.

---

## 6. 시그널 판정

- 판정 규칙은 순수 도메인이다 (`signals.py`의 `SignalState.advance`, `InvestorFlowState` 등)
- **직전 상태는 메모리에 둔다.** 일자가 바뀌면 버린다. 예외는 순매수 흐름 정점으로, `market_flow_state`에 남겨 재시작 후 복원한다
- 종목 시그널 감시 풀은 등락률 -12% 이상(`LEADING_STOCK_SIGNAL_EVENT_MIN_CHANGE_RATE`)
- 쿨다운 — 같은 종목·같은 타입은 `cooldown_minutes`(기본 3분) 동안 재적재하지 않는다
- `NET_BUY_LEVEL`은 같은 날 같은 (시장·투자자·방향·단계)를 DB로 확인해 재시작 후에도 다시 울리지 않는다

---

## 7. 스케줄러

APScheduler `BackgroundScheduler`, 시각 기준 KST 고정(해외 잡은 `America/New_York`). `SCHEDULERS_ENABLED=false`면 아무 잡도 띄우지 않는다(테스트·로컬).

### 7.1 폴러

| 잡 id | 주기 | 동작 시간 | 내용 |
|-------|------|-----------|------|
| `trading-value-pool-refresher` | 10초 | 08:00~20:00 KST | 국내 후보 풀 갱신 |
| `overseas-ranking-pool-refresher` | 10초 | 04:00~20:00 뉴욕 | 해외 후보 풀 갱신 |
| `today-minute-syncer` | 20초 | 08:00~20:00 KST | 감시 풀 당일 1분봉 이어 받기(토스) |
| `today-minute-settler` | 20:01 | 평일 KST | 감시 풀 당일 1분봉을 마감 확정해 지난 날 보관소(메모리·`stock_minute_candle`)에 넘기기 — 다음 날 아침 어제 봉을 키움에서 다시 받지 않게. 2주 지난 봉도 여기서 지운다 |
| `overseas-minute-settler` | 20:01 | 평일 뉴욕 | 들고 있는(최근 10분 안에 연) 해외 종목의 당일 1분봉을 마감 확정해 `overseas_minute_candle`에 넘기기. 2주 지난 봉도 여기서 지운다 |
| `signal-event-poller` | 10초 | 08:00~20:00 KST | 종목 시그널 전이 |
| `market-signal-event-poller` | 60초 | 08:00~20:00 KST | 시장 순매수 단계·흐름 전환, 투자자 스냅샷 |
| `index-rebound-poller` | 30초 | 09:00~15:30 KST | 지수 5분봉 20이평 |
| `futures-investor-poller` | 60초 | 08:45~15:45 KST | 선물 투자자 스냅샷 |

전부 휴장일에는 쉰다.

### 7.2 스냅샷 (cron, 평일)

| 잡 id | 시각 | 내용 |
|-------|------|------|
| `stock-catalog-refresh` | 07:40 KST 매일 | 종목 마스터 갱신 — NXT 프리마켓(08:00) 전. 기동 시에도 1회 |
| `leader-timeline-domestic` | 매분 0초, 08:00~20:00 KST | 국내 주도주 1분 스냅샷 (장 전환 구간 제외) |
| `leader-timeline-overseas` | 매분 0초, 04:00~20:00 뉴욕 | 해외 주도주 1분 스냅샷 |
| `leader-calendar-domestic` | 20:01 KST | 국내 마감 주도주 — 풀이 멈춘 뒤라 그날 밤 첫 화면 값과 같다 |
| `leader-calendar-overseas` | 16:01 뉴욕 | 해외 정규장 마감 주도주 |

- 타임라인·캘린더는 **풀 캐시를 읽기만 한다.** 브로커 호출이 늘지 않는다
- 해외 잡을 KST로 걸지 않는 이유 — 서머타임이 끝나면(11월) 한 시간 어긋난다

---

## 8. REST API

응답은 `{code, status, data}` 봉투로 감싼다 (`library/web.ApiResponse`). 검증 실패는 400 `INVALID_PARAMETER`, 미로그인은 401 `UNAUTHORIZED`.

### 8.1 인증 관문

`/api`는 **기본 차단**이다 (`main._require_login` 미들웨어 + `auth/gate.py` 허용목록). 엔드포인트마다 의존성을 붙이면 새로 추가할 때 빠뜨린 쪽이 열린 채 남는다. 여기서는 빠뜨리면 막히므로 사고가 노출이 아니라 불편으로 끝난다.

미리보기처럼 **일부만 여는 경로는 엔드포인트가 직접 잘라** 내려보낸다. 화면에서 자르면 나머지가 이미 브라우저에 도착해 개발자도구로 읽힌다. `total_count`는 자르기 전 수를 줘서 받는 쪽이 잘렸는지 안다.

### 8.2 엔드포인트

`공개`는 허용목록, `미리보기`는 공개하되 미로그인이면 잘라서 준다. 표시 없는 것은 로그인 필요.

```
# 주도주 (국내)
GET /api/leading-stocks/candidates?minChangeRate=      공개
GET /api/leading-stocks/leaders                        공개   # 주도주 5 + 상한가
GET /api/leading-stocks/breakout-radar?mode=           미리보기 3
GET /api/leading-stocks/signal-events?date=            미리보기 3 (등락률 0% 이상)
GET /api/leading-stocks/market-signal-events?date=
GET /api/leading-stocks/market/investor-net-buy
GET /api/leading-stocks/index/{market}/minute-candles
GET /api/leading-stocks/candidates/{code}                      # 필터 평가 상세
GET /api/leading-stocks/candidates/{code}/minute-candles
GET /api/leading-stocks/candidates/{code}/daily-candles

# 주도주 (해외)
GET /api/overseas-leading-stocks/candidates?minChangeRate=   공개
GET /api/overseas-leading-stocks/leaders                     공개
GET /api/overseas-leading-stocks/{exchange}/{symbol}
GET /api/overseas-leading-stocks/{exchange}/{symbol}/minute-candles
GET /api/overseas-leading-stocks/{exchange}/{symbol}/daily-candles

# 주도주 기록
GET /api/leader-timeline?market=kr|us&date=&since=HH:MM   공개
GET /api/leader-calendar?month=                  공개

# 시황
GET /api/market/calendar/status?region=          공개
GET /api/market/kospi | /kosdaq                  공개
GET /api/market/nasdaq/quote                     공개
GET /api/market/macro/quotes                     공개
GET /api/market/{market}/candles                 공개
GET /api/market/futures/{market}/quote|candles   공개   # night, nasdaq 포함
GET /api/market/nasdaq/candles
GET /api/market/macro/candles?target=&interval=
GET /api/market/investor/today                           # 첫 화면 "오늘의 수급"
GET /api/market/{market}/investor/daily | /investor/sessions
GET /api/market/futures/{market}/investor/daily | /investor/sessions

# 종목
GET /api/stocks/search?q=
GET /api/stocks/{code}/investor/daily

# 인증·의견
GET  /api/auth/login | /callback | /me           공개
POST /api/auth/logout                            공개
POST /api/auth/withdraw                          # 탈퇴 — 가입 기록과 보낸 의견을 함께 삭제
POST /api/feedback                                       # 500자
```

`/health`(이벤트 루프에서 바로 응답), `/health/db`, `/metrics`는 `/api` 밖이다. **`/metrics`를 지키는 것은 인증이 아니라 라우팅이다** — nginx는 `/api/`만 백엔드로 넘긴다. nginx에 `/metrics`를 추가하면 인터넷에 열린다.

---

## 9. 인증

- 구글 OAuth Authorization Code (authlib). client secret이 브라우저로 나가지 않는다
- 세션은 **서명 쿠키** (Starlette `SessionMiddleware`, `judozoo_session`, `https_only`, `SameSite=Lax`). 프론트와 API가 같은 오리진이라 JWT의 이점이 없고 로그아웃만 어려워진다
- `SESSION_SECRET`이 바뀌면 기존 세션이 전부 끊긴다(데이터는 무사)
- 구글 설정이 비어 있으면 로그인 라우트가 503을 준다. 로컬에서 안 채워도 나머지는 돈다
- 가입·의견 알림은 백엔드가 보내지 않는다. Grafana가 테이블을 보고 Slack으로 보낸다. 앱이 웹훅을 들면 시크릿이 늘고, 전송 실패가 사용자 요청의 실패가 된다

---

## 10. 프론트엔드

### 10.1 갱신 전략

실시간 푸시 대신 **화면별 폴링 주기**를 둔다 (`frontend/src/api/queries.ts`).

| 데이터 | `refetchInterval` |
|--------|-------------------|
| 시그널 로그, 눌림·돌파 | 5초 (`LIVE_REFRESH_MS`) |
| 후보 목록, 첫 화면 주도주 | 10초 (`POOL_REFRESH_MS`, 서버 풀 갱신 주기와 같다) |
| 지수·선물 시세, 1분 차트 | 30초 |
| 타임라인(당일), 수급 | 60초 |
| 과거 일자, 일봉 | 폴링 안 함 |

- 기본 `staleTime` 5초, 창 복귀 시 재조회
- 포커스 없는 창도 계속 갱신한다 (듀얼 모니터)

> STOMP/WebSocket을 도입했다가 제거했다. 조회 전용 화면에서 푸시의 이득보다 연결 상태 관리·재연결 정합성 비용이 컸다.

### 10.2 로그인 관문

로그인이 필요한 부분은 `LoginGate`로 대체해 가입을 유도한다. 서버가 401을 주는 것과 별개로, 화면은 미리보기를 보여준 뒤 관문을 띄운다.

### 10.3 검색 노출

`vite build` 뒤 `scripts/build-seo.mjs`가 경로별 정적 HTML(메타 태그)과 `sitemap.xml`을 굽는다. 페이지뷰는 GA4 `gtag`로 보낸다.

---

## 11. 관측

| 축 | 경로 |
|----|------|
| 로그 | 운영은 JSON 한 줄(`LOG_FORMAT=json`), 로컬은 평문. 요청마다 사용자 id·메서드·경로·trace id를 싣는다. alloy → Loki |
| 메트릭 | HTTP는 Instrumentator, 그 밖에 스케줄러 잡·외부 HTTP 호출·스레드풀·리미터 대기 (`library/metrics.py`). Prometheus가 스크레이프 |
| 트레이스 | OpenTelemetry — FastAPI·httpx·SQLAlchemy 자동 계측 + 스케줄러 잡 경계 스팬. alloy(OTLP) → Tempo. `OTLP_ENDPOINT`가 비면 꺼진다 |

- `/health`, `/metrics`는 메트릭·트레이스에서 뺀다. 헬스체크와 5초 폴링에 뒤덮이지 않게
- 잡이 스스로 삼킨 예외는 APScheduler에 성공으로 보이므로 실패 카운터를 직접 올린다
- 관측 스택(Loki·Prometheus·Tempo·Grafana)은 **ops VM**에 있다. 앱 VM이 죽어도 로그가 남고, 배포가 관측을 흔들지 않는다

---

## 12. 테스트 전략

`.claude/rules/testing.md`를 따른다. pytest, `class Test<이름>` + `def test_<설명>`, 이름은 **한글**로 시나리오와 의미를 쓴다. `tests/`는 `src/backend/`와 1:1로 대응한다.

| 계층 | 방식 |
|------|------|
| Domain | 모킹·DB 없이 실제 객체로. 필터 경계 조건, 시그널 상태 전이, 주도주 점수 |
| Platform / Infrastructure | respx로 외부 응답 시뮬레이션 — 성공·실패·타임아웃·이상 페이로드. 브로커 응답 함정이 여기 |
| Application | `통합_db` fixture (Testcontainers MySQL) + `@pytest.mark.integration`. 외부 API만 `pytest-mock`으로 격리 |
| Presentation | `client` fixture(TestClient) + application 모킹 — 라우트·공개/미리보기 경계·응답 형태 |

Docker가 없으면 `uv run pytest -m "not integration"`.

---

## 13. 배포

```
사용자 → judozoo.com (Cloudflare DNS만, 프록시 꺼짐)
       → GCP 전역 외부 부하 분산기 (TLS: Certificate Manager, 정적 파일: Cloud CDN, 압축)
       → 앱 VM :3000 nginx(frontend) ── /api → backend:8000
                                          ├→ Cloud SQL (private IP, VPC 피어링)
                                          └→ 브로커 API (고정 외부 IP — 키움 허용목록)
         앱 VM alloy ──────────────────────→ ops VM (Loki·Prometheus·Tempo·Grafana, 외부 IP 없음)
```

- **부하 분산기가 유일한 공개 입구다.** 방화벽은 tcp 3000을 구글 부하 분산기 대역에만, SSH는 IAP 대역에만 연다
- **Grafana에 공개 경로가 없다.** IAP TCP 터널로만 붙는다 ([`iap-tunnel.md`](./iap-tunnel.md))
- DB는 Cloud SQL — PITR로 논리적 손상을 초 단위로 되감는다 ([018](./tasks/018-cloud-sql-이관.md))
- Terraform (`infra/terraform/`) — 앱 VM + ops VM, 고정 IP, Cloud NAT, Cloud SQL, 부하 분산기·인증서, Artifact Registry, Secret Manager, 백업 버킷, Workload Identity Federation
- GitHub Actions — WIF 인증 → 이미지 빌드·푸시 → ssh 배포(`infra/deploy/remote_deploy.sh`) → 헬스체크. 이미지 태그는 서비스 디렉터리의 git 트리 해시라, 안 바뀐 서비스는 재시작하지 않는다
- compose — 앱 VM `docker-compose.yml` + `docker-compose.prod.yml` (backend·frontend·alloy), ops VM `docker-compose.ops*.yml`
- 앱 인스턴스는 **1대**다. 폴러·캐시·시그널 직전 상태가 프로세스 안에 있으므로 수평 확장하려면 수집과 서빙을 먼저 분리해야 한다

---

## 14. 설정 (`settings.py`)

pydantic-settings. 코드에 기본값을 두고 환경변수(`secrets/.env`)가 덮어쓴다.

```
DatabaseSettings        DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD
KisSettings             REAL_KIS_APP_KEY / REAL_KIS_APP_SECRET / KIS_QUERY_PERMITS_PER_SECOND
KiwoomSettings          REAL_KIWOOM_APP_KEY / REAL_KIWOOM_APP_SECRET / KIWOOM_QUERY_PERMITS_PER_SECOND
TossSettings            REAL_TOSS_CLIENT_ID / REAL_TOSS_CLIENT_SECRET
StockMasterSettings     KIS 마스터 파일 CDN URL (국내·해외)
GoogleOAuthSettings     GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REDIRECT_URI

LeadingStockCriteria    LEADING_STOCK_CRITERIA_*
  min_market_cap: 3000                    # 억원
  max_trading_value_rank: 35
  min_daily_price_change_rate: 7.0        # %
  max_high_position_drop_rate: -5.0       # 60봉 고가 대비 %
  max_prev_close_change_rate: 25.0
  max_opening_price_change_rate: 7.0
  min_program_net_buy: 0                  # 백만원
  ...
SignalEventSettings     LEADING_STOCK_SIGNAL_EVENT_
  min_change_rate: -12.0 / poll_interval_millis: 10000 / cooldown_minutes: 3
MarketSignalSettings    LEADING_STOCK_MARKET_SIGNAL_
  poll_interval_millis: 60000 / candle_poll_interval_millis: 30000 / session_start: 09:00 / session_end: 15:30

SESSION_SECRET          세션 쿠키 서명 키
LOG_FORMAT              json | text
OTLP_ENDPOINT           비면 트레이스 끔
SCHEDULERS_ENABLED      false면 폴러·스냅샷을 띄우지 않는다
```
