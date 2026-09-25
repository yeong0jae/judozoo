"""해외 후보 풀 갱신 폴러 — 미국 장중에만 돈다."""

from backend.overseasleadingstock import application, scheduler


def 미국장(monkeypatch, 휴장: bool, 거래시간: bool) -> list:
    monkeypatch.setattr("backend.market.calendar.us_market_status", lambda: (휴장, 거래시간))
    호출됨: list = []
    monkeypatch.setattr(application, "refresh_ranking_pool", lambda: 호출됨.append(1))
    return 호출됨


class Test해외_후보_풀_갱신:
    def test_미국_장중이면_후보_풀을_갈아_끼운다(self, monkeypatch):
        호출됨 = 미국장(monkeypatch, 휴장=False, 거래시간=True)
        scheduler.refresh_ranking_pool()
        assert 호출됨 == [1]

    def test_미국_휴장이면_갱신하지_않는다(self, monkeypatch):
        호출됨 = 미국장(monkeypatch, 휴장=True, 거래시간=True)
        scheduler.refresh_ranking_pool()
        assert 호출됨 == []

    def test_미국_장_시간이_아니면_갱신하지_않는다(self, monkeypatch):
        호출됨 = 미국장(monkeypatch, 휴장=False, 거래시간=False)
        scheduler.refresh_ranking_pool()
        assert 호출됨 == []

    def test_실패해도_폴러가_죽지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.us_market_status", lambda: (False, True))

        def 터진다():
            raise RuntimeError("KIS 오류")

        monkeypatch.setattr(application, "refresh_ranking_pool", 터진다)

        scheduler.refresh_ranking_pool()
