"""주도주 API — 응답 계약과 파라미터 처리."""

from datetime import date, datetime

import pytest

from backend.leadingstock import application, events
from backend.leadingstock.domain import LeadingStockSnapshot, SwingHighSignal
from backend.leadingstock.entities import MarketSignalEvent, SignalEvent
from backend.leadingstock.filters import FilterEvaluationResult
from backend.leadingstock.signals import InvestorType, MarketSignalType, NetTradeSide, SignalEventType
from backend.library.db import get_engine, get_session_factory
from backend.stock.domain import Market

AT = datetime(2026, 9, 11, 10, 0)
오늘 = date(2026, 9, 11)


def 종목(code="005930", name="삼성전자") -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=code, stock_name=name, current_price=70_000, price_change_rate=7.5,
        trading_value_rank=3, accumulated_trading_value=1_000_000_000_000,
    )


@pytest.fixture
def 후보_대역(monkeypatch):
    monkeypatch.setattr(application, "find_candidate_stocks", lambda _r: [종목()])


class Test후보_목록:
    def test_순위를_붙여_돌려준다(self, client, 후보_대역):
        본문 = client.get("/api/leading-stocks/candidates").json()

        assert 본문["code"] == "SUCCESS"
        데이터 = 본문["data"]
        assert 데이터["totalCount"] == 1
        종목항목 = 데이터["stocks"][0]
        assert 종목항목["rank"] == 1
        assert 종목항목["stockCode"] == "005930"

    def test_등락률_임계값을_범위로_자른다(self, client, monkeypatch):
        """사용자가 -12~7 중에서 고른다 — 밖의 값은 경계로 붙인다."""
        받은값 = []
        monkeypatch.setattr(
            application, "find_candidate_stocks", lambda r: 받은값.append(r) or []
        )

        client.get("/api/leading-stocks/candidates", params={"minChangeRate": 99})
        client.get("/api/leading-stocks/candidates", params={"minChangeRate": -99})

        assert 받은값 == [7.0, -12.0]

    def test_미지정이면_설정_기본값을_쓴다(self, client, monkeypatch):
        받은값 = []
        monkeypatch.setattr(
            application, "find_candidate_stocks", lambda r: 받은값.append(r) or []
        )

        client.get("/api/leading-stocks/candidates")

        assert 받은값 == [7.0]  # application.yaml 기준값


class Test종목_상세:
    def test_필터_평가와_돌파_시그널을_함께_준다(self, 로그인_client, monkeypatch):
        평가 = application.StockEvaluation(
            stock=종목(),
            filter_results=[FilterEvaluationResult("거래대금순위", "상위 35위 이내", "3위", True)],
            relative_volume=2.5,
            swing_high_signal=SwingHighSignal(peak_price=72_000, peak_at=AT, gap_rate=2.86),
        )
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: 평가)

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert 데이터["relativeVolume"] == 2.5
        assert 데이터["swingHighSignal"]["peakPrice"] == 72_000
        assert 데이터["filterResults"][0]["filterName"] == "거래대금순위"
        assert 데이터["filterResults"][0]["passed"] is True

    def test_돌파_시그널이_없으면_null(self, 로그인_client, monkeypatch):
        평가 = application.StockEvaluation(종목(), [], None, None)
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: 평가)

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert 데이터["swingHighSignal"] is None
        assert 데이터["relativeVolume"] is None


@pytest.fixture
def 이벤트_테이블(통합_db):
    for model in (SignalEvent, MarketSignalEvent):
        model.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as s:
        s.query(SignalEvent).delete()
        s.query(MarketSignalEvent).delete()
        s.commit()
    yield


class Test시그널_로그:
    def test_최신순으로_돌려준다(self, 로그인_client, 이벤트_테이블):
        with get_session_factory()() as s:
            for i, 분 in enumerate([0, 5, 10]):
                s.add(SignalEvent(
                    occurred_at=datetime(2026, 9, 11, 10, 분), trade_date=오늘,
                    stock_code=f"00000{i}", stock_name=f"종목{i}",
                    event_type=SignalEventType.VOLUME_SPIKE.value,
                    current_price=1000, price_change_rate=5.0, trading_value=100,
                    created_at=AT, updated_at=AT,
                ))
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert 데이터["totalCount"] == 3
        assert [e["stockName"] for e in 데이터["events"]] == ["종목2", "종목1", "종목0"]

    def test_다른_날짜는_섞이지_않는다(self, 로그인_client, 이벤트_테이블):
        with get_session_factory()() as s:
            s.add(SignalEvent(
                occurred_at=AT, trade_date=date(2026, 9, 10),
                stock_code="005930", stock_name="어제것",
                event_type=SignalEventType.VOLUME_SPIKE.value,
                current_price=1000, price_change_rate=5.0, trading_value=100,
                created_at=AT, updated_at=AT,
            ))
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert 데이터["totalCount"] == 0


class Test시장_시그널_로그:
    def test_단계에_해당하는_임계액을_함께_준다(self, 로그인_client, 이벤트_테이블):
        """코스피는 1조 단위 — 3단계면 3조."""
        with get_session_factory()() as s:
            s.add(MarketSignalEvent(
                occurred_at=AT, trade_date=오늘, kind=MarketSignalType.NET_BUY_LEVEL.value,
                market=Market.KOSPI, side=NetTradeSide.BUY, investor=InvestorType.FOREIGN,
                level=3, net_amount_eok=32_000, index_value=2653.81, change_rate=1.2,
                created_at=AT, updated_at=AT,
            ))
            s.commit()

        항목 = 로그인_client.get(
            "/api/leading-stocks/market-signal-events", params={"date": "2026-09-11"}
        ).json()["data"]["events"][0]

        assert 항목["kind"] == "NET_BUY_LEVEL"
        assert 항목["thresholdEok"] == 30_000
        assert (항목["market"], 항목["side"], 항목["investor"]) == ("KOSPI", "BUY", "FOREIGN")

    def test_단계가_없는_종류는_임계액도_null(self, 로그인_client, 이벤트_테이블):
        with get_session_factory()() as s:
            s.add(MarketSignalEvent(
                occurred_at=AT, trade_date=오늘, kind=MarketSignalType.MA20_REBOUND.value,
                market=Market.KOSDAQ, side=NetTradeSide.BUY,
                created_at=AT, updated_at=AT,
            ))
            s.commit()

        항목 = 로그인_client.get(
            "/api/leading-stocks/market-signal-events", params={"date": "2026-09-11"}
        ).json()["data"]["events"][0]

        assert 항목["thresholdEok"] is None
        assert 항목["level"] is None
        assert 항목["kind"] == "MA20_REBOUND"


class Test지수_캔들:
    def test_알_수_없는_시장은_INTERNAL_ERROR_봉투로_떨어진다(self, 통합_db):
        """Kotlin도 `Market.valueOf`가 던져 INTERNAL_ERROR가 된다 — 순수 이관이라 같게 뒀다.

        TestClient는 기본적으로 서버 예외를 되던져 핸들러를 건너뛰므로,
        운영과 같은 경로를 보려면 `raise_server_exceptions=False`가 필요하다.
        """
        from fastapi.testclient import TestClient

        from backend.main import app

        from tests.conftest import 세션_쿠키

        with TestClient(app, raise_server_exceptions=False) as c:
            # 관문 뒤 경로다 — 쿠키가 없으면 401이라 500 핸들러까지 가지 않는다.
            c.cookies.set("judozoo_session", 세션_쿠키())
            응답 = c.get("/api/leading-stocks/index/NIKKEI/minute-candles")

        assert 응답.status_code == 500
        assert 응답.json()["code"] == "INTERNAL_ERROR"


class Test브로커_토큰_백오프:
    """발급 백오프는 **예상된 상태**다 — 스택트레이스 없이 한 줄만 남기고 오류 봉투를 준다.

    화면이 5초마다 폴링하므로 트레이스를 찍으면 로그가 트레이스로 뒤덮인다
    (2026-09-12에 실제로 그랬다).
    """

    def test_봉투는_Kotlin과_같은_500_INTERNAL_ERROR다(self, monkeypatch, caplog):
        from fastapi.testclient import TestClient

        from backend.leadingstock import application as app_mod
        from backend.main import app
        from backend.platform.kiwoom.client import KiwoomTokenUnavailable

        def 백오프(_rate):
            raise KiwoomTokenUnavailable("토큰 발급 백오프 중 — 52초 후 재시도")

        monkeypatch.setattr(app_mod, "find_candidate_stocks", 백오프)

        with TestClient(app, raise_server_exceptions=False) as c:
            응답 = c.get("/api/leading-stocks/candidates")

        assert 응답.status_code == 500
        assert 응답.json()["code"] == "INTERNAL_ERROR"

    def test_스택트레이스를_남기지_않는다(self, monkeypatch, caplog):
        import logging

        from fastapi.testclient import TestClient

        from backend.leadingstock import application as app_mod
        from backend.main import app
        from backend.platform.kiwoom.client import KiwoomTokenUnavailable

        monkeypatch.setattr(
            app_mod, "find_candidate_stocks",
            lambda _r: (_ for _ in ()).throw(KiwoomTokenUnavailable("백오프 중 — 52초")),
        )

        # lifespan의 configure_logging이 caplog 핸들러를 밀어내므로 컨텍스트 매니저를 쓰지 않는다
        client = TestClient(app, raise_server_exceptions=False)
        with caplog.at_level(logging.WARNING):
            client.get("/api/leading-stocks/candidates")

        백오프_기록 = [r for r in caplog.records if "백오프" in r.getMessage()]
        assert 백오프_기록, "백오프를 한 줄로 남겨야 한다"
        assert all(r.exc_info is None for r in 백오프_기록), "트레이스를 붙이면 안 된다"
        assert all(r.levelno == logging.WARNING for r in 백오프_기록)
