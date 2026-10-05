"""주도주 API — 응답 계약과 파라미터 처리."""

from datetime import date, datetime

import pytest

from backend.leadingstock import application, events
from backend.leadingstock.domain import LeadingStockSnapshot
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


class Test첫_화면_주도주:
    @pytest.fixture(autouse=True)
    def 상한가_없음(self, monkeypatch):
        """상한가를 말하지 않는 시험은 빈 목록을 기본값으로 둔다."""
        monkeypatch.setattr(application, "find_limit_ups", lambda: [])

    def test_미로그인도_볼_수_있고_순위가_붙는다(self, client, monkeypatch):
        monkeypatch.setattr(
            application, "find_leaders",
            lambda _c: [종목("005930", "삼성전자"), 종목("000660", "SK하이닉스")],
        )

        본문 = client.get("/api/leading-stocks/leaders").json()

        assert 본문["code"] == "SUCCESS"
        주도주 = 본문["data"]["leaders"]
        assert [s["rank"] for s in 주도주] == [1, 2]
        assert [s["stockName"] for s in 주도주] == ["삼성전자", "SK하이닉스"]

    def test_등락률_파라미터를_받지_않는다(self, client, monkeypatch):
        """첫 화면은 목록 화면에 걸어둔 기준과 무관하게 같은 답을 줘야 한다."""
        받은값 = []
        monkeypatch.setattr(
            application, "find_leaders", lambda c: 받은값.append(c) or []
        )

        client.get("/api/leading-stocks/leaders", params={"minChangeRate": -12})

        assert 받은값 == [5]  # 파라미터와 무관하게 늘 다섯 칸


class Test첫_화면_상한가:
    def test_주도주와_한_응답에_함께_온다(self, client, monkeypatch):
        """따로 부르면 호출 사이에 후보 풀이 갱신돼 둘이 다른 순간을 말할 수 있다."""
        monkeypatch.setattr(application, "find_leaders", lambda _c: [종목("005930", "삼성전자")])
        monkeypatch.setattr(
            application, "find_limit_ups",
            lambda: [종목("900001", "대성하이텍"), 종목("900002", "미래산업")],
        )

        데이터 = client.get("/api/leading-stocks/leaders").json()["data"]

        assert [s["stockName"] for s in 데이터["leaders"]] == ["삼성전자"]
        assert [s["stockName"] for s in 데이터["limitUps"]] == ["대성하이텍", "미래산업"]

    def test_칩에_필요한_이름과_코드만_싣는다(self, client, monkeypatch):
        """시세를 실으면 화면이 안 쓰는 값이 매 폴링마다 오간다."""
        monkeypatch.setattr(application, "find_leaders", lambda _c: [])
        monkeypatch.setattr(application, "find_limit_ups", lambda: [종목("900001", "대성하이텍")])

        칩 = client.get("/api/leading-stocks/leaders").json()["data"]["limitUps"][0]

        assert 칩 == {"stockCode": "900001", "stockName": "대성하이텍"}

    def test_없는_날은_빈_목록이_온다(self, client, monkeypatch):
        """후보 컷이 거래대금 35위라 0건이 기본값이다 — 화면은 이때 띠를 그리지 않는다."""
        monkeypatch.setattr(application, "find_leaders", lambda _c: [종목()])
        monkeypatch.setattr(application, "find_limit_ups", lambda: [])

        데이터 = client.get("/api/leading-stocks/leaders").json()["data"]

        assert 데이터["limitUps"] == []


class Test종목_상세:
    def test_필터_평가와_상대거래량을_함께_준다(self, 로그인_client, monkeypatch):
        평가 = application.StockEvaluation(
            stock=종목(),
            filter_results=[FilterEvaluationResult("거래대금순위", "상위 35위 이내", "3위", True, 3, 35)],
            relative_volume=2.5,
        )
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: 평가)

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert 데이터["relativeVolume"] == 2.5
        assert 데이터["filterResults"][0]["filterName"] == "거래대금순위"
        assert 데이터["filterResults"][0]["passed"] is True
        assert (데이터["filterResults"][0]["value"], 데이터["filterResults"][0]["threshold"]) == (3, 35)

    def test_상대거래량이_없으면_null(self, 로그인_client, monkeypatch):
        평가 = application.StockEvaluation(종목(), [], None)
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: 평가)

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert 데이터["relativeVolume"] is None

    def test_시가_고가_저가_전일과_거래대금을_싣는다(self, 로그인_client, monkeypatch):
        """상세 머리가 전일 대비와 시고저를 그린다 — 등락률에서 역산하면 큰 가격대에서 원 단위가 어긋난다."""
        시세 = LeadingStockSnapshot(
            stock_code="005930", stock_name="삼성전자", current_price=286_500, price_change_rate=3.62,
            trading_value_rank=1, accumulated_trading_value=9_100_600_000_000,
            opening_price=283_000, previous_close=276_500, high_price=289_500, low_price=281_500,
        )
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: application.StockEvaluation(시세, [], None))

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert (데이터["openingPrice"], 데이터["highPrice"], 데이터["lowPrice"]) == (283_000, 289_500, 281_500)
        assert 데이터["previousClose"] == 276_500
        assert 데이터["tradingValue"] == 9_100_600_000_000

    def test_거래대금_순위_밖이면_거래대금은_null(self, 로그인_client, monkeypatch):
        """순위 밖 종목은 누적 거래대금을 받아오지 않는다 — 0원으로 그리면 거래가 없던 것처럼 읽힌다."""
        순위밖 = LeadingStockSnapshot(
            stock_code="005930", stock_name="삼성전자", current_price=70_000, price_change_rate=1.0,
            trading_value_rank=0, accumulated_trading_value=0,
        )
        monkeypatch.setattr(application, "evaluate_stock", lambda _c: application.StockEvaluation(순위밖, [], None))

        데이터 = 로그인_client.get("/api/leading-stocks/candidates/005930").json()["data"]

        assert 데이터["tradingValue"] is None


class Test눌림_돌파_미리보기:
    @staticmethod
    def 레이더_대역(monkeypatch, 개수):
        """전부 돌파선 3% 안에 든 종목 — 가까운 순으로 0.1%씩 벌려 둔다."""
        from backend.leadingstock.application import BreakoutRadarStock

        monkeypatch.setattr(application, "breakout_radar", lambda: [
            BreakoutRadarStock(
                stock_code=f"{i:06d}", stock_name=f"종목{i:02d}",
                current_price=1000, price_change_rate=7.5,
                peak_price=1100, peak_at=AT, gap_rate=0.1 * i, trading_value=100,
            )
            for i in range(개수)
        ])

    def test_미로그인은_상위_세개만_받는다(self, client, monkeypatch):
        """화면에서 자르면 나머지가 브라우저까지 내려가 읽힌다 — 서버가 잘라야 한다."""
        self.레이더_대역(monkeypatch, 8)

        데이터 = client.get("/api/leading-stocks/breakout-radar").json()["data"]

        assert len(데이터["stocks"]) == 3
        # 자르기 전 전체 수는 그대로 알려준다 — 받는 쪽이 "몇 개 더 있는지"를 말할 수 있게
        assert 데이터["totalCount"] == 8
        assert 데이터["stocks"][0]["stockName"] == "종목00"

    def test_선에서_먼_종목은_목록에_없다(self, client, monkeypatch):
        """전체 수도 거른 뒤 기준이라 "N개 중 M개"가 같은 모수를 센다."""
        from backend.leadingstock.application import BreakoutRadarStock

        monkeypatch.setattr(application, "breakout_radar", lambda: [
            BreakoutRadarStock(
                stock_code=code, stock_name=code, current_price=1000, price_change_rate=7.5,
                peak_price=1100, peak_at=AT, gap_rate=갭, trading_value=100,
            )
            for code, 갭 in [("가까움", 0.5), ("경계", 3.0), ("멂", 3.1), ("아주멂", 12.0)]
        ])

        데이터 = client.get("/api/leading-stocks/breakout-radar").json()["data"]

        assert [s["stockCode"] for s in 데이터["stocks"]] == ["가까움", "경계"]
        assert 데이터["totalCount"] == 2

    def test_모드마다_자기_선으로_거르고_세운다(self, client, monkeypatch):
        """돌파 모드는 돌파선, 눌림 모드는 눌림선 — 각자 3% 안에 든 것만 본다."""
        from backend.leadingstock.application import BreakoutRadarStock

        def 종목(code, 돌파갭, 눌림갭):
            return BreakoutRadarStock(
                stock_code=code, stock_name=f"종목{code}",
                current_price=1000, price_change_rate=-2.0,
                peak_price=1100, peak_at=AT, gap_rate=돌파갭, trading_value=100,
                support_gap_rate=눌림갭,
            )

        monkeypatch.setattr(application, "breakout_radar", lambda: [
            종목("A", 1.0, 9.0), 종목("B", 2.0, 8.0), 종목("C", 3.0, 7.0),
            종목("D", 4.0, 6.0), 종목("E", 5.0, 5.0), 종목("F", 6.0, 0.5),
        ])

        돌파 = client.get("/api/leading-stocks/breakout-radar").json()["data"]
        눌림 = client.get(
            "/api/leading-stocks/breakout-radar", params={"mode": "support"}
        ).json()["data"]

        assert [s["stockCode"] for s in 돌파["stocks"]] == ["A", "B", "C"]
        # 눌림선 3% 안은 F뿐 — 돌파선으로는 가장 먼 종목이다
        assert [s["stockCode"] for s in 눌림["stocks"]] == ["F"]

    def test_눌림선을_못_구한_종목은_눌림_모드에서_빠진다(self, client, monkeypatch):
        from backend.leadingstock.application import BreakoutRadarStock

        monkeypatch.setattr(application, "breakout_radar", lambda: [
            BreakoutRadarStock(
                stock_code="눌림없음", stock_name="눌림없음", current_price=1000,
                price_change_rate=7.5, peak_price=1100, peak_at=AT, gap_rate=0.5,
                trading_value=100, support_gap_rate=None,
            )
        ])

        데이터 = client.get(
            "/api/leading-stocks/breakout-radar", params={"mode": "support"}
        ).json()["data"]

        assert 데이터["stocks"] == []
        assert 데이터["totalCount"] == 0

    def test_로그인하면_다섯개_넘게도_전부_받는다(self, 로그인_client, monkeypatch):
        self.레이더_대역(monkeypatch, 8)

        데이터 = 로그인_client.get("/api/leading-stocks/breakout-radar").json()["data"]

        assert len(데이터["stocks"]) == 8


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

    def test_미로그인은_최신_세건만_받는다(self, client, 이벤트_테이블):
        """화면에서 자르면 나머지가 브라우저까지 내려가 읽힌다 — 서버가 잘라야 한다."""
        with get_session_factory()() as s:
            for i in range(12):
                s.add(SignalEvent(
                    occurred_at=datetime(2026, 9, 11, 10, i), trade_date=오늘,
                    stock_code=f"{i:06d}", stock_name=f"종목{i:02d}",
                    event_type=SignalEventType.VOLUME_SPIKE.value,
                    current_price=1000, price_change_rate=5.0, trading_value=100,
                    created_at=AT, updated_at=AT,
                ))
            s.commit()

        데이터 = client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert len(데이터["events"]) == 3
        # 자르기 전 전체 건수는 그대로 알려준다 — 받는 쪽이 "몇 건 더 있는지"를 말할 수 있게
        assert 데이터["totalCount"] == 12
        assert 데이터["events"][0]["stockName"] == "종목11"

    def test_미로그인은_내린_종목의_전이를_못_본다(self, client, 이벤트_테이블):
        """감시 풀이 -12%까지 넓어, 거르지 않으면 미리보기가 급락주 전이로 찰 수 있다."""
        with get_session_factory()() as s:
            for i, 등락률 in enumerate([-8.0, -0.1, 0.0, 9.0]):
                s.add(SignalEvent(
                    occurred_at=datetime(2026, 9, 11, 10, i), trade_date=오늘,
                    stock_code=f"{i:06d}", stock_name=f"종목{i:02d}",
                    event_type=SignalEventType.VOLUME_SPIKE.value,
                    current_price=1000, price_change_rate=등락률, trading_value=100,
                    created_at=AT, updated_at=AT,
                ))
            s.commit()

        데이터 = client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert [e["stockName"] for e in 데이터["events"]] == ["종목03", "종목02"]
        # 거른 뒤가 전체 건수다 — 로그인해도 못 볼 건수를 "더 있다"로 세지 않는다
        assert 데이터["totalCount"] == 2

    def test_로그인하면_등락률과_무관하게_전부_받는다(self, 로그인_client, 이벤트_테이블):
        with get_session_factory()() as s:
            for i, 등락률 in enumerate([-8.0, 9.0]):
                s.add(SignalEvent(
                    occurred_at=datetime(2026, 9, 11, 10, i), trade_date=오늘,
                    stock_code=f"{i:06d}", stock_name=f"종목{i:02d}",
                    event_type=SignalEventType.VOLUME_SPIKE.value,
                    current_price=1000, price_change_rate=등락률, trading_value=100,
                    created_at=AT, updated_at=AT,
                ))
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert 데이터["totalCount"] == 2

    def test_로그인하면_열건_넘게도_전부_받는다(self, 로그인_client, 이벤트_테이블):
        with get_session_factory()() as s:
            for i in range(12):
                s.add(SignalEvent(
                    occurred_at=datetime(2026, 9, 11, 10, i), trade_date=오늘,
                    stock_code=f"{i:06d}", stock_name=f"종목{i:02d}",
                    event_type=SignalEventType.VOLUME_SPIKE.value,
                    current_price=1000, price_change_rate=5.0, trading_value=100,
                    created_at=AT, updated_at=AT,
                ))
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/signal-events", params={"date": "2026-09-11"}).json()["data"]

        assert len(데이터["events"]) == 12

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
                occurred_at=AT, trade_date=오늘, kind=MarketSignalType.MA_REBOUND.value,
                market=Market.KOSDAQ, side=NetTradeSide.BUY,
                created_at=AT, updated_at=AT,
            ))
            s.commit()

        항목 = 로그인_client.get(
            "/api/leading-stocks/market-signal-events", params={"date": "2026-09-11"}
        ).json()["data"]["events"][0]

        assert 항목["thresholdEok"] is None
        assert 항목["level"] is None
        assert 항목["kind"] == "MA_REBOUND"


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


@pytest.fixture
def 수급_스냅샷_테이블(통합_db):
    from backend.leadingstock.infrastructure import MarketInvestorSnapshot

    MarketInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as s:
        s.query(MarketInvestorSnapshot).delete()
        s.commit()
    yield


def 수급_스냅샷(market: Market, at: datetime, 외인: int):
    from backend.leadingstock.infrastructure import MarketInvestorSnapshot

    return MarketInvestorSnapshot(
        market=market, trade_date=at.date(), captured_at=at,
        foreign_eok=외인, institution_eok=0, individual_eok=0, other_corp_eok=0,
        index_value=2600.5, change_rate=1.2, created_at=at, updated_at=at,
    )


@pytest.mark.integration
class Test시장_수급_라이브:
    def test_키움을_부르지_않고_가장_최근_스냅샷을_준다(self, 로그인_client, 수급_스냅샷_테이블, mocker):
        from backend.leadingstock import presentation

        mocker.patch.object(presentation, "now", return_value=AT)
        키움 = mocker.patch("backend.platform.kiwoom.sector_investor.fetch_sector_net_buy")
        with get_session_factory()() as s:
            s.add_all([
                수급_스냅샷(Market.KOSPI, datetime(2026, 9, 11, 9, 58), 100),
                수급_스냅샷(Market.KOSPI, datetime(2026, 9, 11, 9, 59), 200),
                수급_스냅샷(Market.KOSPI, datetime(2026, 9, 11, 10, 1), 999),
                수급_스냅샷(Market.KOSDAQ, datetime(2026, 9, 11, 9, 59), -50),
            ])
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/market/investor-net-buy").json()["data"]

        assert [(d["market"], d["foreignEok"], d["indexValue"]) for d in 데이터] == [
            ("KOSPI", 200, 2600.5), ("KOSDAQ", -50, 2600.5),
        ]
        키움.assert_not_called()

    def test_장_전에는_직전_거래일_마지막_스냅샷을_준다(self, 로그인_client, 수급_스냅샷_테이블, mocker):
        from backend.leadingstock import presentation

        mocker.patch.object(presentation, "now", return_value=datetime(2026, 9, 14, 7, 0))
        with get_session_factory()() as s:
            s.add_all([
                수급_스냅샷(Market.KOSPI, datetime(2026, 9, 11, 19, 59), 300),
                수급_스냅샷(Market.KOSPI, datetime(2026, 9, 11, 20, 0), 400),
            ])
            s.commit()

        데이터 = 로그인_client.get("/api/leading-stocks/market/investor-net-buy").json()["data"]

        assert [(d["market"], d["foreignEok"]) for d in 데이터] == [("KOSPI", 400)]
