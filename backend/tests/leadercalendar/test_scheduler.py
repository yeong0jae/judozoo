"""주도주 캘린더 스냅샷 작업 — 휴장일은 건너뛰고, 거래일은 현지 날짜로 잡는다."""

from datetime import date

from backend.leadercalendar import application, scheduler
from backend.market.calendar import Region


def 찍기_대역(monkeypatch, 이름: str) -> list:
    찍힘: list = []
    monkeypatch.setattr(application, 이름, lambda _s, 거래일, _at: 찍힘.append(거래일))
    monkeypatch.setattr(scheduler, "get_session_factory", lambda: _가짜_세션)
    return 찍힘


class _가짜_세션:
    def __enter__(self):
        return None

    def __exit__(self, *_):
        return False


class Test국내_스냅샷_작업:
    def test_국내_휴장일에는_찍지_않는다(self, monkeypatch):
        찍힘 = 찍기_대역(monkeypatch, "snapshot_domestic")
        monkeypatch.setattr("backend.market.calendar.is_holiday", lambda region: region is Region.KR)

        scheduler.snapshot_domestic()

        assert 찍힘 == []

    def test_실패해도_작업이_죽지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.is_holiday", lambda _r: False)
        monkeypatch.setattr(scheduler, "get_session_factory", lambda: _가짜_세션)

        def 터진다(*_):
            raise RuntimeError("키움 오류")

        monkeypatch.setattr(application, "snapshot_domestic", 터진다)

        scheduler.snapshot_domestic()


class Test해외_스냅샷_작업:
    def test_뉴욕_날짜를_거래일로_잡는다(self, monkeypatch):
        """16:01 뉴욕은 KST로 다음 날 새벽이다 — 한국 날짜를 쓰면 하루 밀린다."""
        찍힘 = 찍기_대역(monkeypatch, "snapshot_overseas")
        monkeypatch.setattr("backend.market.calendar.is_holiday", lambda _r: False)
        monkeypatch.setattr(Region, "today", lambda self: date(2026, 9, 25) if self is Region.US else date(2026, 9, 26))

        scheduler.snapshot_overseas()

        assert 찍힘 == [date(2026, 9, 25)]

    def test_미국_휴장일에는_찍지_않는다(self, monkeypatch):
        찍힘 = 찍기_대역(monkeypatch, "snapshot_overseas")
        monkeypatch.setattr("backend.market.calendar.is_holiday", lambda region: region is Region.US)

        scheduler.snapshot_overseas()

        assert 찍힘 == []
