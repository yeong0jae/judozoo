"""왜 오르나 — 언제 만들고, 무엇을 믿고, 무엇을 보여주는가."""

from dataclasses import dataclass
from datetime import date, datetime, time

import pytest

from backend.insight.domain import (
    SCHEDULES,
    Attempt,
    Attempts,
    Draft,
    Source,
    Sources,
    Trigger,
    clean_title,
    judge,
    pick,
    shown,
)
from backend.market.calendar import Region

KR = SCHEDULES[Region.KR]
US = SCHEDULES[Region.US]


def at(hh: int, mm: int, day: date = date(2026, 9, 28)) -> datetime:
    return datetime.combine(day, time(hh, mm))


class Test국내_생성_시간:
    @pytest.mark.parametrize("t", [time(8, 30), time(12, 0), time(20, 0)])
    def test_0830부터_2000까지_만든다(self, t):
        assert KR.in_window(t)

    @pytest.mark.parametrize("t", [time(8, 0), time(8, 29), time(20, 1), time(23, 0)])
    def test_장_초반_30분과_애프터마켓_뒤에는_만들지_않는다(self, t):
        assert not KR.in_window(t)

    @pytest.mark.parametrize("t", [time(8, 30), time(9, 30), time(11, 0), time(14, 0), time(16, 0), time(18, 0), time(20, 0)])
    def test_정해진_시각은_일곱_번이다(self, t):
        assert KR.is_slot(t)

    def test_정해진_시각은_5분_폭으로_본다(self):
        """앞 실행이 1분을 넘겨 정각 실행이 건너뛰어져도 그 시각을 놓치지 않는다. 겹쳐 만드는 건 30분 규칙이 막는다."""
        assert KR.is_slot(time(9, 34))
        assert not KR.is_slot(time(9, 35))


class Test해외_생성_시간:
    """해외 시각은 뉴욕 기준이다 — 서머타임이 바뀌어도 미국 장의 같은 순간에 돈다."""

    @pytest.mark.parametrize("t", [time(10, 0), time(11, 0), time(13, 0), time(16, 30), time(18, 0)])
    def test_정해진_시각은_뉴욕_기준_다섯_번이다(self, t):
        assert US.is_slot(t)

    @pytest.mark.parametrize("t", [time(4, 0), time(9, 59), time(18, 1)])
    def test_프리마켓_초반과_1800_뒤에는_만들지_않는다(self, t):
        assert not US.in_window(t)


class Test기사_범위:
    def test_국내는_직전_거래일_1530_이후다(self):
        assert KR.articles_since(date(2026, 9, 25)) == datetime(2026, 9, 25, 15, 30)

    def test_해외는_직전_거래일_뉴욕_1600_이후다(self):
        assert US.articles_since(date(2026, 9, 24)) == datetime(2026, 9, 24, 16, 0)


class Test직전_평일:
    def test_월요일의_직전_평일은_금요일이다(self):
        from backend.insight.domain import previous_weekday
        assert previous_weekday(date(2026, 9, 28)) == date(2026, 9, 25)


class Test보여줄_세션_날짜:
    def test_프리마켓_시작_전에는_직전_거래일을_보여준다(self):
        assert KR.session_day(at(7, 59), today_open=True, previous_open_day=date(2026, 9, 25)) == date(2026, 9, 25)

    def test_프리마켓이_시작되면_오늘로_넘어간다(self):
        assert KR.session_day(at(8, 0), today_open=True, previous_open_day=date(2026, 9, 25)) == date(2026, 9, 28)

    def test_휴장일에는_하루_종일_직전_거래일에_머문다(self):
        day = date(2026, 9, 24)
        assert KR.session_day(at(12, 0, day), today_open=False, previous_open_day=date(2026, 9, 23)) == date(2026, 9, 23)

    def test_해외는_뉴욕_0400에_넘어간다(self):
        ny = date(2026, 9, 25)
        assert US.session_day(at(3, 59, ny), today_open=True, previous_open_day=date(2026, 9, 24)) == date(2026, 9, 24)
        assert US.session_day(at(4, 0, ny), today_open=True, previous_open_day=date(2026, 9, 24)) == ny


def made(hh: int, mm: int, ok: bool = True, retry: bool = False, explained: bool = True) -> Attempt:
    return Attempt(at=at(hh, mm), published=ok, retry=retry, explained=explained)


class Test만들_종목_고르기:
    def test_생성_시간_밖에서는_아무것도_고르지_않는다(self):
        assert pick(KR, ["A"], {}, at(8, 10)) == []

    def test_새로_들어온_종목은_정해진_시각이_아니어도_바로_만든다(self):
        assert pick(KR, ["A"], {}, at(10, 15)) == [("A", Trigger.ENTRY)]

    def test_이미_만든_종목은_정해진_시각이_아니면_건드리지_않는다(self):
        assert pick(KR, ["A"], {"A": Attempts([made(10, 16)])}, at(10, 40)) == []

    def test_정해진_시각에는_주도주를_다시_만든다(self):
        assert pick(KR, ["A"], {"A": Attempts([made(9, 30)])}, at(11, 0)) == [("A", Trigger.SCHEDULED)]

    def test_정해진_시각이라도_30분_안에_만든_종목은_건너뛴다(self):
        history = {"A": Attempts([made(10, 41)]), "B": Attempts([made(9, 30)])}
        assert pick(KR, ["A", "B"], history, at(11, 0)) == [("B", Trigger.SCHEDULED)]

    def test_실패하면_3분_뒤_한_번_다시_시도한다(self):
        history = {"A": Attempts([made(10, 15, ok=False)])}
        assert pick(KR, ["A"], history, at(10, 17)) == []
        assert pick(KR, ["A"], history, at(10, 18)) == [("A", Trigger.RETRY)]

    def test_다시_시도도_실패하면_다음_정해진_시각까지_기다린다(self):
        history = {"A": Attempts([made(10, 15, ok=False), made(10, 18, ok=False, retry=True)])}
        assert pick(KR, ["A"], history, at(10, 30)) == []

    def test_후보는_새로_들어올_때_바로_만든다(self):
        assert pick(KR, ["A"], {}, at(10, 15), candidates=["C"]) == [("A", Trigger.ENTRY), ("C", Trigger.ENTRY)]

    def test_사유가_있는_후보는_정해진_시각에_다시_만들지_않는다(self):
        """후보는 한 번 만든 사유로 둔다 — 비용 때문. 주도주로 올라오면 그때부터 정해진 시각마다 다시 만든다."""
        history = {"A": Attempts([made(9, 30)]), "C": Attempts([made(9, 30)])}
        assert pick(KR, ["A"], history, at(11, 0), candidates=["C"]) == [("A", Trigger.SCHEDULED)]

    def test_설명_없음인_후보는_정해진_시각에_다시_만든다(self):
        """급등 직후엔 기사가 아직 없는 경우가 많다 — 사유가 나올 때까지 정해진 시각마다 한 번씩 더 본다."""
        history = {"C": Attempts([made(9, 30, explained=False)])}
        assert pick(KR, [], history, at(11, 0), candidates=["C"]) == [("C", Trigger.SCHEDULED)]

    def test_설명_없음이_나중에_나왔어도_앞서_설명된_후보는_다시_만들지_않는다(self):
        history = {"C": Attempts([made(9, 30), made(10, 0, explained=False)])}
        assert pick(KR, [], history, at(11, 0), candidates=["C"]) == []

    def test_설명_없음인_후보도_정해진_시각이_아니면_기다린다(self):
        history = {"C": Attempts([made(9, 30, explained=False)])}
        assert pick(KR, [], history, at(10, 40), candidates=["C"]) == []

    def test_후보도_실패하면_3분_뒤_한_번_다시_시도한다(self):
        history = {"C": Attempts([made(10, 15, ok=False)])}
        assert pick(KR, [], history, at(10, 18), candidates=["C"]) == [("C", Trigger.RETRY)]

    def test_주도주와_후보에_같은_종목이_있으면_주도주로_본다(self):
        history = {"A": Attempts([made(9, 30)])}
        assert pick(KR, ["A"], history, at(11, 0), candidates=["A"]) == [("A", Trigger.SCHEDULED)]

    def test_주도주에서_빠진_종목은_다시_만들지_않는다(self):
        assert pick(KR, ["B"], {"A": Attempts([made(9, 30)])}, at(11, 0)) == [("B", Trigger.ENTRY)]


class Test기사_제목_정리:
    @pytest.mark.parametrize(
        "raw, title",
        [
            ("LG이노텍, 장 초반 10% 강세 [종목 NOW] - 파이낸셜뉴스", "LG이노텍, 장 초반 10% 강세 [종목 NOW]"),
            ("코리아써키트 7만원 돌파 < 증시 < 금융 < 경제 < 기사본문 - 잡포스트", "코리아써키트 7만원 돌파"),
            ("Bloom Energy Stock Surges As S&amp;P 500 Deals Align", "Bloom Energy Stock Surges As S&P 500 Deals Align"),
            ("엔비디아가 콕 집었다…LG전자 껑충 | 한국경제", "엔비디아가 콕 집었다…LG전자 껑충"),
        ],
    )
    def test_매체명_사이트_경로_엔티티를_걷어낸다(self, raw, title):
        assert clean_title(raw) == title


class Test출처_목록:
    def test_시세_중계_기사는_번호를_받지_못한다(self):
        sources = Sources.of([
            ("fnnews.com", "LG이노텍, 美라이다 기업 지분가치 상승 영향", "https://a"),
            ("topstarnews.net", "LG이노텍 주가, 9월 28일 장중 579,000원 8.22% 상승", "https://b"),
            ("247wallst.com", "Apple (AAPL) Sets a New 52-Week High at $341.07", "https://c"),
            ("gurufocus.com", "Tech Stocks Surge as Apple (AAPL) Hits Record High", "https://d"),
        ])
        assert [s.url for s in sources] == ["https://a"]

    def test_같은_기사는_한_번만_싣는다(self):
        sources = Sources.of([("a.com", "같은 기사의 제목입니다", "https://x"), ("a.com", "같은 기사의 제목입니다", "https://x")])
        assert len(sources) == 1

    def test_기사가_아닌_사이트_제목은_뺀다(self):
        sources = Sources.of([("alphasquare.co.kr", "알파스퀘어", "https://home"), ("daum.net", "대우건설, 용인 반도체 국가산단 1공구 공사 따냈다", "https://a")])
        assert [s.url for s in sources] == ["https://a"]

    def test_포털을_거친_매체명은_원래_매체만_남긴다(self):
        sources = Sources.of([("Daum | 서울경제", "대우건설, 용인 반도체 국가산단 1공구 공사 따냈다", "https://a")])
        assert sources.items[0].domain == "서울경제"

    def test_번호는_1부터_차례로_붙는다(self):
        sources = Sources.of([("a.com", "첫 번째 기사 제목입니다", "https://1"), ("b.com", "두 번째 기사 제목입니다", "https://2")])
        assert [s.id for s in sources] == [1, 2]


SOURCES = Sources([
    Source(1, "fnnews.com", "LG이노텍, 美라이다 기업 지분가치 상승 영향", "https://1"),
    Source(2, "daum.net", "LG이노텍, 美라이다 업체 베팅 결실", "https://2"),
    Source(3, "jkn.co.kr", "LG이노텍, 호재성 뉴스 부재 속 급등", "https://3"),
])


def draft(**kw) -> Draft:
    base = dict(explained=True, keywords=["Aeva", "라이다"], reason="美 라이다 업체 Aeva 지분가치 상승", evidence=[1, 2], related=[3])
    return Draft(**{**base, **kw})


class Test답_검사:
    def test_맞는_답은_그대로_게시한다(self):
        v = judge(draft(), SOURCES)
        assert v.published and v.explained
        assert [s.url for s in v.evidence] == ["https://1", "https://2"]
        assert [s.url for s in v.related] == ["https://3"]

    def test_사유_끝의_마침표는_뗀다(self):
        v = judge(draft(reason="자사주 매입 추가 승인에 매수세 유입."), SOURCES)
        assert v.reason == "자사주 매입 추가 승인에 매수세 유입"

    def test_목록에_없는_번호를_대면_거절한다(self):
        v = judge(draft(evidence=[1, 9]), SOURCES)
        assert not v.published
        assert "번호" in v.error

    def test_권유_표현이_있으면_거절한다(self):
        assert not judge(draft(reason="지분가치 상승, 지금 매수할 때"), SOURCES).published
        assert not judge(draft(reason="증권가 목표가 상향"), SOURCES).published

    def test_일반어_키워드는_그_칩만_뺀다(self):
        v = judge(draft(keywords=["상승", "라이다"]), SOURCES)
        assert v.published and v.keywords == ["라이다"]

    def test_종목_자신의_이름은_키워드가_아니다(self):
        v = judge(draft(keywords=["LG이노텍", "라이다"]), SOURCES, stock_name="LG이노텍")
        assert v.keywords == ["라이다"]

    def test_너무_긴_키워드는_뺀다(self):
        v = judge(draft(keywords=["미국 라이다 업체 지분가치", "Aeva"]), SOURCES)
        assert v.keywords == ["Aeva"]

    def test_근거_기사가_없는_사유는_설명_없음으로_내린다(self):
        v = judge(draft(evidence=[], related=[3]), SOURCES)
        assert v.published and not v.explained
        assert v.reason == "" and v.keywords == []
        assert [s.url for s in v.related] == ["https://3"]

    def test_설명_없음이면_사유와_칩을_비운다(self):
        v = judge(draft(explained=False, reason="뭔가", evidence=[1]), SOURCES)
        assert not v.explained and v.reason == "" and v.keywords == [] and v.evidence == []

    def test_근거와_관련에_같은_기사가_있으면_근거에만_둔다(self):
        v = judge(draft(evidence=[1], related=[1, 3]), SOURCES)
        assert [s.id for s in v.related] == [3]


@dataclass
class Row:
    generated_at: datetime
    published: bool
    explained: bool


class Test보여줄_사유:
    def test_가장_최근의_설명된_사유를_보여준다(self):
        rows = [Row(at(8, 30), True, True), Row(at(9, 30), True, True)]
        assert shown(rows) is rows[1]

    def test_나중에_나온_설명_없음은_앞의_사유를_덮지_않는다(self):
        rows = [Row(at(9, 30), True, True), Row(at(11, 0), True, False)]
        assert shown(rows) is rows[0]

    def test_설명된_사유가_없으면_가장_최근의_설명_없음을_쓴다(self):
        rows = [Row(at(8, 30), True, False), Row(at(9, 30), True, False)]
        assert shown(rows) is rows[1]

    def test_거절된_답은_보여주지_않는다(self):
        rows = [Row(at(8, 30), True, True), Row(at(9, 30), False, True)]
        assert shown(rows) is rows[0]

    def test_게시된_답이_없으면_아무것도_없다(self):
        assert shown([Row(at(8, 30), False, True)]) is None
