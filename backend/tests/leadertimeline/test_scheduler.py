"""1분 스냅샷 작업 — 현지 분으로 찍고, 쉬는 구간·휴장일은 건너뛴다."""

from datetime import datetime

from backend.leadertimeline import application, scheduler
from backend.market.calendar import Region


class _가짜_세션:
    def __enter__(self):
        return None

    def __exit__(self, *_):
        return False


def 찍기_대역(monkeypatch, 지금: datetime, 휴장: bool = False) -> list:
    찍힘: list = []
    monkeypatch.setattr(scheduler, "now", lambda: 지금)
    monkeypatch.setattr(scheduler, "get_session_factory", lambda: _가짜_세션)
    monkeypatch.setattr("backend.market.calendar.is_holiday", lambda _r: 휴장)
    monkeypatch.setattr(application, "snapshot", lambda _s, 시장, 분, _t: 찍힘.append((시장, 분)))
    return 찍힘


class Test국내_1분_작업:
    def test_그_분을_초_없이_찍는다(self, monkeypatch):
        찍힘 = 찍기_대역(monkeypatch, datetime(2026, 9, 23, 10, 15, 0, 420000))

        scheduler.snapshot_domestic()

        assert 찍힘 == [(Region.KR, datetime(2026, 9, 23, 10, 15))]

    def test_장_사이_쉬는_구간은_찍지_않는다(self, monkeypatch):
        찍힘 = 찍기_대역(monkeypatch, datetime(2026, 9, 23, 15, 33))

        scheduler.snapshot_domestic()

        assert 찍힘 == []

    def test_휴장일은_찍지_않는다(self, monkeypatch):
        찍힘 = 찍기_대역(monkeypatch, datetime(2026, 9, 24, 10, 15), 휴장=True)

        scheduler.snapshot_domestic()

        assert 찍힘 == []

    def test_실패해도_작업이_죽지_않는다(self, monkeypatch):
        찍기_대역(monkeypatch, datetime(2026, 9, 23, 10, 15))

        def 터진다(*_):
            raise RuntimeError("키움 오류")

        monkeypatch.setattr(application, "snapshot", 터진다)

        scheduler.snapshot_domestic()


class Test해외_1분_작업:
    def test_뉴욕_시각으로_찍는다(self, monkeypatch):
        """KST 9/24 00:30 = 뉴욕 9/23 11:30 (서머타임)."""
        찍힘 = 찍기_대역(monkeypatch, datetime(2026, 9, 24, 0, 30))

        scheduler.snapshot_overseas()

        assert 찍힘 == [(Region.US, datetime(2026, 9, 23, 11, 30))]

    def test_서머타임이_끝나면_한_시간_더_뒤로_간다(self, monkeypatch):
        """KST 11/10 00:30 = 뉴욕 11/9 10:30 (표준시)."""
        찍힘 = 찍기_대역(monkeypatch, datetime(2026, 11, 10, 0, 30))

        scheduler.snapshot_overseas()

        assert 찍힘 == [(Region.US, datetime(2026, 11, 9, 10, 30))]
