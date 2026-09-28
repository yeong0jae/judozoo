"""왜 오르나 — 매분 실행이 무엇을 만들고 남기는가, 화면에 무엇을 내주는가.

Vertex·기사 사이트·주도주 풀은 바깥이라 mock으로 바꾸고, DB는 컨테이너 MySQL을 쓴다.
"""

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


@pytest.fixture
def 바깥(mocker):
    """주도주 풀·Vertex·기사 사이트. 기본은 LG이노텍 하나가 제대로 설명되는 경우."""
    mocker.patch.object(application.leadingstock, "find_leaders", return_value=[국내("011070", "LG이노텍")])
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

    def test_Vertex가_실패해도_행을_남기고_3분_뒤_한_번_다시_한다(self, 빈_테이블, 바깥):
        ground, _ = 바깥
        ground.side_effect = [VertexError("HTTP 429"), VertexError("HTTP 429"), VertexError("HTTP 429")]
        with 세션() as s:
            application.run(s, Region.KR, kst(10, 15))
            application.run(s, Region.KR, kst(10, 17))
            application.run(s, Region.KR, kst(10, 18))
            application.run(s, Region.KR, kst(10, 25))

        rows = 행들()
        assert [(r.trigger, r.published) for r in rows] == [("entry", False), ("retry", False)]
        assert "429" in rows[0].error

    def test_하루_상한에_닿으면_그날은_멈춘다(self, 빈_테이블, 바깥, monkeypatch):
        monkeypatch.setenv("VERTEX_DAILY_LIMIT", "1")
        application.get_settings.cache_clear()
        application.leadingstock.find_leaders.return_value = [국내("011070", "LG이노텍"), 국내("009830", "한화솔루션")]
        with 세션() as s:
            assert application.run(s, Region.KR, kst(10, 15)) == 1
        assert len(행들()) == 1


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
