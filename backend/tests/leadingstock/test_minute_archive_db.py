"""지난 날 분봉 보관소의 DB 층 — 재시작해도 어제 봉이 남아야 아침에 키움을 다시 부르지 않는다."""

from datetime import date, datetime

import pytest

from backend.leadingstock import minute_archive
from backend.leadingstock.domain import MinuteCandle
from backend.leadingstock.infrastructure import StockMinuteCandleEntity
from backend.library import db


def 봉(날짜: date, 시: int, 분: int = 0, 종가: int = 100) -> MinuteCandle:
    return MinuteCandle(
        date_time=datetime(날짜.year, 날짜.month, 날짜.day, 시, 분), open_price=종가, high_price=종가 + 1,
        low_price=종가 - 1, close_price=종가, volume=10, trading_value=종가 * 10,
    )


@pytest.fixture
def 보관소_DB(통합_db, monkeypatch):
    monkeypatch.setattr(minute_archive, "get_session_factory", db.get_session_factory)
    StockMinuteCandleEntity.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(StockMinuteCandleEntity).delete()
        s.commit()
    minute_archive.reset()
    yield
    minute_archive.reset()


@pytest.mark.integration
class Test지난_날_분봉_DB:
    def test_메모리가_비어도_DB에서_그날_봉을_꺼낸다(self, 보관소_DB):
        """배포로 재시작한 상황."""
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9, 1), 봉(date(2026, 9, 25), 9, 0)])
        minute_archive.reset()

        꺼낸_봉 = minute_archive.get("005930", date(2026, 9, 25))

        assert [c.date_time.minute for c in 꺼낸_봉] == [0, 1]
        assert 꺼낸_봉[0].high_price == 101 and 꺼낸_봉[0].trading_value == 1000

    def test_같은_날을_다시_넣으면_통째로_갈아_끼운다(self, 보관소_DB):
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9, 0, 종가=100)])
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9, 0, 종가=105), 봉(date(2026, 9, 25), 9, 1)])
        minute_archive.reset()

        assert [c.close_price for c in minute_archive.get("005930", date(2026, 9, 25))] == [105, 100]

    def test_DB에_없는_날은_없다고_답한다(self, 보관소_DB):
        assert minute_archive.get("005930", date(2026, 9, 25)) is None

    def test_보관_기간이_지난_날은_지운다(self, 보관소_DB):
        minute_archive.put("005930", date(2026, 9, 10), [봉(date(2026, 9, 10), 9)])
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9)])

        지운_행 = minute_archive.purge(date(2026, 9, 14))
        minute_archive.reset()

        assert 지운_행 == 1
        assert minute_archive.get("005930", date(2026, 9, 10)) is None
        assert minute_archive.get("005930", date(2026, 9, 25)) is not None


class TestDB가_죽어_있으면:
    def test_메모리만으로_넣고_꺼낸다(self):
        """테스트 기본값이 곧 DB가 죽은 상황이다(conftest가 DB를 막는다)."""
        minute_archive.reset()
        minute_archive.put("005930", date(2026, 9, 25), [봉(date(2026, 9, 25), 9)])

        assert minute_archive.get("005930", date(2026, 9, 25)) is not None
        minute_archive.reset()
