"""지난 거래일 1분봉 보관소 — 완성된 날만, 날짜 단위로."""

from datetime import date, datetime

import pytest

from backend.leadingstock import minute_archive
from backend.leadingstock.domain import MinuteCandle


def 봉(날짜: date, 시: int) -> MinuteCandle:
    return MinuteCandle(
        date_time=datetime(날짜.year, 날짜.month, 날짜.day, 시, 0), open_price=1, high_price=1,
        low_price=1, close_price=1, volume=1, trading_value=1,
    )


@pytest.fixture(autouse=True)
def 비우기():
    minute_archive.reset()
    yield
    minute_archive.reset()


class Test보관:
    def test_넣은_날을_날짜로_꺼낸다(self):
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 10), 봉(date(2026, 9, 25), 9)])

        assert [c.date_time.hour for c in minute_archive.get("005930", date(2026, 9, 25))] == [9, 10]

    def test_랭킹_코드와_맨_코드는_같은_종목이다(self):
        minute_archive.put("005930_AL", date(2026, 9, 25), [봉(date(2026, 9, 25), 9)])

        assert minute_archive.get("005930", date(2026, 9, 25)) is not None

    def test_빈_날은_넣지_않는다(self):
        """못 받은 날을 '봉 없는 날'로 굳히지 않는다."""
        minute_archive.put("005930", date(2026, 9, 25), [])

        assert minute_archive.get("005930", date(2026, 9, 25)) is None

    def test_수명이_지나면_내주지_않는다(self, monkeypatch):
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9)])
        지금 = minute_archive.time.monotonic()
        monkeypatch.setattr(minute_archive.time, "monotonic", lambda: 지금 + minute_archive._TTL_SECONDS + 1)

        assert minute_archive.get("005930", date(2026, 9, 25)) is None

    def test_넘치면_오래_넣은_것부터_밀어낸다(self, monkeypatch):
        monkeypatch.setattr(minute_archive, "_MAX_ENTRIES", 2)
        for 일 in (22, 23, 25):
            minute_archive.put("005930", date(2026, 9, 일), [봉(date(2026, 9, 일), 9)])

        assert minute_archive.get("005930", date(2026, 9, 22)) is None
        assert minute_archive.get("005930", date(2026, 9, 25)) is not None


class Test키움_페이지:
    def test_가장_이른_날은_잘려_있어_빼고_넣는다(self):
        minute_archive.put_page("005930", [봉(date(2026, 9, 23), 9), 봉(date(2026, 9, 22), 19)])

        assert minute_archive.get("005930", date(2026, 9, 23)) is not None
        assert minute_archive.get("005930", date(2026, 9, 22)) is None
