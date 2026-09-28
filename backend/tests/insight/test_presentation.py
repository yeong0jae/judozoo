"""왜 오르나 API — 공개 범위와 응답 모양."""

from datetime import date, datetime

from backend.insight import application
from backend.insight.entities import StockReason
from backend.market.calendar import Region


def 사유(explained: bool = True) -> StockReason:
    return StockReason(
        region=Region.KR, trading_day=date(2026, 9, 28), code="011070", name="LG이노텍",
        generated_at=datetime(2026, 9, 28, 10, 41), trigger="entry", published=True, explained=explained,
        keywords=["Aeva", "라이다"] if explained else [], reason="美 라이다 업체 Aeva 지분가치 상승" if explained else "",
        evidence=[{"source": "파이낸셜뉴스", "title": "지분가치 상승 영향", "url": "https://fnnews.com/1"}] if explained else [],
        related=[{"source": "재경일보", "title": "호재성 뉴스 부재 속 급등", "url": "https://jkn.co.kr/2"}],
        model="gemini-3.8-flash", error=None,
    )


class Test왜_오르나_API:
    def test_방문자는_키워드와_사유까지_보고_기사는_건수만_받는다(self, client, mocker):
        mocker.patch.object(application, "reasons", return_value=[사유()])

        [item] = client.get("/api/insight/reasons", params={"market": "kr"}).json()["data"]

        assert item == {
            "code": "011070", "explained": True, "keywords": ["Aeva", "라이다"],
            "reason": "美 라이다 업체 Aeva 지분가치 상승", "generatedAt": "2026-09-28T10:41:00",
            "evidenceCount": 1, "relatedCount": 1, "evidence": None, "related": None,
        }

    def test_로그인하면_근거와_관련_기사를_받는다(self, 로그인_client, mocker):
        mocker.patch.object(application, "reasons", return_value=[사유()])

        [item] = 로그인_client.get("/api/insight/reasons", params={"market": "kr"}).json()["data"]

        assert item["evidence"] == [{"source": "파이낸셜뉴스", "title": "지분가치 상승 영향", "url": "https://fnnews.com/1"}]
        assert item["related"][0]["url"] == "https://jkn.co.kr/2"

    def test_설명_없음도_관련_기사_건수와_함께_내려간다(self, client, mocker):
        mocker.patch.object(application, "reasons", return_value=[사유(explained=False)])

        [item] = client.get("/api/insight/reasons", params={"market": "kr"}).json()["data"]

        assert (item["explained"], item["reason"], item["relatedCount"]) == (False, "", 1)

    def test_해외는_us로_조회한다(self, client, mocker):
        조회 = mocker.patch.object(application, "reasons", return_value=[])

        client.get("/api/insight/reasons", params={"market": "us"})

        assert 조회.call_args.args[1] == Region.US

    def test_시장이_틀리면_거절한다(self, client, mocker):
        mocker.patch.object(application, "reasons", return_value=[])

        assert client.get("/api/insight/reasons", params={"market": "jp"}).json()["code"] == "INVALID_PARAMETER"
