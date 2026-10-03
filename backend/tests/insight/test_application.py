"""왜 오르나 — 매분 실행이 무엇을 만들고 남기는가, 화면에 무엇을 내주는가.

Vertex·기사 사이트·주도주 풀은 바깥이라 mock으로 바꾸고, DB는 컨테이너 MySQL을 쓴다.
"""

import threading
from datetime import date, datetime

import pytest

from backend.insight import application
from backend.insight.domain import Trigger
from backend.insight.entities import StockReason
from backend.leadingstock.domain import LeadingStockSnapshot
from backend.library import db
from backend.market.calendar import Region
from backend.platform.vertex.articles import Article
from backend.platform.vertex.client import Grounded, GroundedSource, Usage, VertexError

오늘 = date(2026, 9, 28)


def kst(h: int, m: int, day: date = 오늘) -> datetime:
    return datetime(day.year, day.month, day.day, h, m)


def 국내(code: str, name: str) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=f"{code}_AL", stock_name=name, current_price=575_000, price_change_rate=7.48,
        trading_value_rank=1, accumulated_trading_value=340_700_000_000,
    )


@pytest.fixture
def 빈_테이블(통합_db):
    StockReason.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(StockReason).delete()
        s.commit()


@pytest.fixture(autouse=True)
def 서킷_초기화():
    application.reset()
    yield
    application.reset()


@pytest.fixture
def 바깥(mocker):
    """주도주 풀·Vertex·기사 사이트. 기본은 LG이노텍 하나가 제대로 설명되는 경우."""
    mocker.patch.object(application.leadingstock, "find_leaders", return_value=[국내("011070", "LG이노텍")])
    mocker.patch.object(application.leadingstock, "find_candidate_stocks", return_value=[])
    mocker.patch.object(application.calendar, "previous_open_day", return_value=date(2026, 9, 25))
    ground = mocker.patch.object(application.vertex, "ground", return_value=Grounded(
        text="美 라이다 업체 Aeva 지분가치가 올랐다.",
        sources=[GroundedSource("fnnews.com", "https://redirect/A"), GroundedSource("jkn.co.kr", "https://redirect/B")],
        usage=Usage(search_queries=3),
    ))
    mocker.patch.object(application.articles, "resolve", side_effect=lambda uri: {
        "https://redirect/A": Article("https://fnnews.com/1", "LG이노텍, 美라이다 기업 지분가치 상승 영향 - 파이낸셜뉴스", "파이낸셜뉴스"),
        "https://redirect/B": Article("https://jkn.co.kr/2", "LG이노텍, 호재성 뉴스 부재 속 급등", None),
    }[uri])
    structure = mocker.patch.object(application.vertex, "structure", return_value=(
        {"explained": True, "keywords": ["Aeva", "LG이노텍"], "reason": "美 라이다 업체 Aeva 지분가치 상승", "evidence": [1], "related": [2]},
        Usage(),
    ))
    return ground, structure


def 세션():
    return db.get_session_factory()()


def 행들() -> list[StockReason]:
    with 세션() as s:
        return list(s.query(StockReason).order_by(StockReason.id).all())


@pytest.mark.integration
class Test사유_만들기:
    def test_새로_들어온_주도주의_사유와_기사를_남긴다(self, 빈_테이블, 바깥):
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 1

        [r] = 행들()
        assert (r.region, r.trading_day, r.code, r.name) == (Region.KR, 오늘, "011070", "LG이노텍")
        assert r.trigger == Trigger.ENTRY.value and r.published and r.explained
        assert r.reason == "美 라이다 업체 Aeva 지분가치 상승"
        assert r.keywords == ["Aeva"]  # 종목 자신의 이름은 뺐다
        assert r.evidence == [{"source": "파이낸셜뉴스", "title": "LG이노텍, 美라이다 기업 지분가치 상승 영향", "url": "https://fnnews.com/1"}]
        assert r.related == [{"source": "jkn.co.kr", "title": "LG이노텍, 호재성 뉴스 부재 속 급등", "url": "https://jkn.co.kr/2"}]

    def test_기준_시각은_실행_시작이_아니라_그_종목을_다_만든_때다(self, 빈_테이블, 바깥, mocker):
        mocker.patch.object(application, "monotonic", side_effect=[0.0, 15.0])
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))

        assert 행들()[0].generated_at == datetime(2026, 9, 28, 10, 15, 15)

    def test_검색_프롬프트에_직전_거래일_장_마감_이후라고_적는다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))

        assert "2026-09-25 15:30 KST 이후" in ground.call_args.args[0]

    def test_생성_시간_밖에는_주도주도_묻지_않는다(self, 빈_테이블, 바깥, mocker):
        with 세션() as s:
            assert application.run(s, Region.KR, kst(8, 10)) == 0
        application.leadingstock.find_leaders.assert_not_called()

    def test_이미_만든_종목은_정해진_시각까지_다시_부르지_않는다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            application.run(s, Region.KR, kst(10, 40))
            application.run(s, Region.KR, kst(11, 0))

        assert ground.call_count == 2
        assert [r.trigger for r in 행들()] == ["entry", "scheduled"]

    def test_Vertex가_아파도_행을_남기고_1분_5분_뒤에_다시_한다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        ground.side_effect = [VertexError("unavailable", "ground HTTP 429"), VertexError("timeout", "ground 요청 실패")]
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            application.run(s, Region.KR, kst(10, 16))
            application.run(s, Region.KR, kst(10, 18))

        rows = 행들()
        assert [(r.trigger, r.published, r.failure) for r in rows] == [
            ("entry", False, "unavailable"), ("retry", False, "timeout"),
        ]
        assert "429" in rows[0].error

    def test_설정이_틀린_오류는_다시_하지_않고_재배포_전까지_Vertex를_부르지_않는다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        ground.side_effect = VertexError("client_error", "ground HTTP 403")
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            application.leadingstock.find_leaders.return_value = [국내("011070", "LG이노텍"), 국내("009830", "한화솔루션")]
            assert application.run(s, Region.KR, kst(11, 0)) == 0

        assert ground.call_count == 1
        assert [r.failure for r in 행들()] == ["client_error"]

    def test_일시_장애가_세_번_이어지면_서킷이_열려_남은_종목은_행_없이_건너뛴다(self, 빈_테이블, 바깥, monkeypatch):
        monkeypatch.setattr(application, "MAX_CONCURRENCY", 1)  # 차례가 정해져야 몇 번째에서 열리는지 본다
        ground, _ = 바깥
        ground.side_effect = VertexError("timeout", "ground 요청 실패")
        application.leadingstock.find_leaders.return_value = [국내(f"00000{i}", f"종목{i}") for i in range(1, 8)]
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 3
            # 5분 동안은 아무것도 부르지 않는다
            assert application.run(s, Region.KR, kst(10, 17)) == 0

        assert ground.call_count == 3
        assert len(행들()) == 3

    def test_서킷이_열린_뒤엔_한_종목으로_시험하고_성공하면_건너뛴_종목을_만든다(self, 빈_테이블, 바깥, monkeypatch):
        monkeypatch.setattr(application, "MAX_CONCURRENCY", 1)
        ground, _ = 바깥
        ground.side_effect = [VertexError("timeout", "느림")] * 3 + [ground.return_value] * 10
        application.leadingstock.find_leaders.return_value = [국내(f"00000{i}", f"종목{i}") for i in range(1, 5)]
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 3  # 넷째는 서킷에 막혀 건너뜀
            assert application.run(s, Region.KR, kst(10, 21)) == 1  # 5분 뒤 한 종목으로 시험
            assert application.run(s, Region.KR, kst(10, 22)) == 3  # 닫혔으니 나머지

        published = [r for r in 행들() if r.published]
        assert sorted(r.code for r in published) == ["000001", "000002", "000003", "000004"]
        assert sorted(r.trigger for r in published) == ["entry", "retry", "retry", "retry"]

    def test_하루_상한에_닿으면_그날은_멈춘다(self, 빈_테이블, 바깥, monkeypatch):
        monkeypatch.setenv("VERTEX_DAILY_LIMIT", "1")
        application.get_settings.cache_clear()
        application.leadingstock.find_leaders.return_value = [국내("011070", "LG이노텍"), 국내("009830", "한화솔루션")]
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 1
        assert len(행들()) == 1


    def test_5퍼센트_넘게_오른_후보는_새로_들어올_때만_만든다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        application.leadingstock.find_candidate_stocks.return_value = [국내("011070", "LG이노텍"), 국내("009830", "한화솔루션")]
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            application.run(s, Region.KR, kst(11, 0))

        rows = [(r.code, r.trigger) for r in 행들()]
        # 같은 실행의 종목은 끝난 순서대로 저장된다
        assert sorted(rows[:2]) == [("009830", "entry"), ("011070", "entry")]
        assert rows[2:] == [("011070", "scheduled")]
        application.leadingstock.find_candidate_stocks.assert_called_with(5.0)

    def test_여러_종목을_동시에_만들어_뒤_종목이_앞_종목을_기다리지_않는다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        application.leadingstock.find_leaders.return_value = [국내("011070", "LG이노텍"), 국내("009830", "한화솔루션")]
        # 두 종목이 모두 1차 호출에 들어와야 풀린다 — 하나씩 만들면 첫 종목이 여기서 막혀 실패한다
        만남 = threading.Barrier(2, timeout=5)
        답 = ground.return_value

        def 둘이_만나야_답한다(prompt):
            만남.wait()
            return 답

        ground.side_effect = 둘이_만나야_답한다
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 2

        assert all(r.published for r in 행들())


@pytest.mark.integration
class Test화면에_내줄_사유:
    def test_종목마다_가장_최근의_설명된_사유_하나(self, 빈_테이블, 바깥, mocker):
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        _, structure = 바깥
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            structure.return_value = ({"explained": False, "keywords": [], "reason": "", "evidence": [], "related": [2]}, Usage())
            application.run(s, Region.KR, kst(11, 0))

            [shown] = application.reasons(s, Region.KR, kst(12, 0))

        assert shown.explained and shown.reason == "美 라이다 업체 Aeva 지분가치 상승"

    def test_프리마켓_시작_전에는_직전_거래일_사유를_내준다(self, 빈_테이블, 바깥, mocker):
        mocker.patch.object(application.calendar, "is_holiday", return_value=False)
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15, date(2026, 9, 25)))

            assert [r.code for r in application.reasons(s, Region.KR, kst(7, 59))] == ["011070"]
            assert application.reasons(s, Region.KR, kst(8, 0)) == []
