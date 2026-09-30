"""해외 지난 날 분봉 보관소의 DB 층 — 재시작해도 끝난 날이 남아야 처음 열 때 KIS를 덜 부른다."""

from datetime import date, datetime

import pytest

from backend.library import db
from backend.overseasleadingstock import minute_archive
from backend.overseasleadingstock.infrastructure import OverseasMinuteCandleEntity
from backend.platform.kis.overseas_chart import OverseasMinuteCandle


def 봉(영업일: date, 한국시각: datetime, 종가: float = 100.5) -> OverseasMinuteCandle:
    return OverseasMinuteCandle(
        date_time=한국시각, trading_day=영업일, open=종가, high=종가 + 1, low=종가 - 1, close=종가,
        volume=10, trading_value=종가 * 10,
    )


@pytest.fixture
def 보관소_DB(통합_db, monkeypatch):
    monkeypatch.setattr(minute_archive, "get_session_factory", db.get_session_factory)
    OverseasMinuteCandleEntity.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(OverseasMinuteCandleEntity).delete()
        s.commit()
    minute_archive.reset()
    yield
    minute_archive.reset()


@pytest.mark.integration
class Test해외_지난_날_분봉_DB:
    def test_메모리가_비어도_최근_날부터_꺼낸다(self, 보관소_DB):
        """배포로 재시작한 상황. 미국 장은 한국 자정을 넘나들어 한 영업일이 한국 날짜 이틀에 걸친다."""
        그제, 어제 = date(2026, 9, 24), date(2026, 9, 25)
        minute_archive.put("NAS", "AAPL", 그제, [봉(그제, datetime(2026, 9, 25, 4, 59))])
        minute_archive.put("NAS", "AAPL", 어제, [봉(어제, datetime(2026, 9, 26, 0, 1)), 봉(어제, datetime(2026, 9, 25, 23, 59))])
        minute_archive.reset()

        꺼낸_날 = minute_archive.recent("NAS", "AAPL", 2)

        assert [d for d, _ in 꺼낸_날] == [어제, 그제]
        assert [c.date_time.hour for c in 꺼낸_날[0][1]] == [23, 0]
        assert 꺼낸_날[0][1][0].close == 100.5 and 꺼낸_날[0][1][0].trading_value == 1005

    def test_다른_종목의_날은_섞이지_않는다(self, 보관소_DB):
        어제 = date(2026, 9, 25)
        minute_archive.put("NAS", "AAPL", 어제, [봉(어제, datetime(2026, 9, 25, 23, 0))])

        assert minute_archive.recent("NAS", "MSFT", 2) == []

    def test_같은_날을_다시_넣으면_통째로_갈아_끼운다(self, 보관소_DB):
        어제 = date(2026, 9, 25)
        minute_archive.put("NAS", "AAPL", 어제, [봉(어제, datetime(2026, 9, 25, 23, 0), 종가=100)])
        minute_archive.put("NAS", "AAPL", 어제, [봉(어제, datetime(2026, 9, 25, 23, 0), 종가=105)])
        minute_archive.reset()

        [(_, 봉들)] = minute_archive.recent("NAS", "AAPL", 2)

        assert [c.close for c in 봉들] == [105]

    def test_기준일_전의_날을_지운다(self, 보관소_DB):
        옛날, 어제 = date(2026, 9, 10), date(2026, 9, 25)
        minute_archive.put("NAS", "AAPL", 옛날, [봉(옛날, datetime(2026, 9, 10, 23, 0))])
        minute_archive.put("NAS", "AAPL", 어제, [봉(어제, datetime(2026, 9, 25, 23, 0))])

        minute_archive.purge(date(2026, 9, 15))
        minute_archive.reset()

        assert [d for d, _ in minute_archive.recent("NAS", "AAPL", 5)] == [어제]


class Test보관소_장애:
    def test_DB가_안_되어도_완성된_지난_봉은_메모리에서_읽는다(self):
        day = date(2026, 9, 25)
        bars = [봉(day, datetime(2026, 9, 25, 23))]
        # conftest가 DB 접근을 차단한 상태에서도 메모리 보관본은 살아 있어야 한다.
        minute_archive.put("NAS", "AAPL", day, bars)
        assert minute_archive.recent("NAS", "AAPL", 2) == [(day, bars)]
