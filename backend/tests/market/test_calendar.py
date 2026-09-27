"""미국 장 상태 — 한국 시간으로 도는 서버가 미 동부 기준으로 판정한다. 서머타임이 함정이다."""

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime

import pytest

from backend.market import calendar


@pytest.fixture
def 평일(monkeypatch):
    monkeypatch.setattr(calendar, "is_holiday", lambda region: False)


def 지금(monkeypatch, kst: datetime) -> None:
    monkeypatch.setattr(calendar, "now", lambda: kst)


class Test미국_장중_판정:
    def test_서머타임엔_한국_17시에_프리마켓이_열린다(self, monkeypatch, 평일):
        지금(monkeypatch, datetime(2026, 9, 25, 17, 0))

        assert calendar.us_market_status() == (False, True)

    def test_정규장이_끝나도_애프터마켓_동안은_열려_있다(self, monkeypatch, 평일):
        지금(monkeypatch, datetime(2026, 9, 26, 5, 0))   # 미 동부 16:00

        assert calendar.us_market_status() == (False, True)

    def test_서머타임엔_한국_아침_9시에_애프터마켓이_끝난다(self, monkeypatch, 평일):
        지금(monkeypatch, datetime(2026, 9, 26, 9, 0))   # 미 동부 20:00

        assert calendar.us_market_status() == (False, False)

    def test_표준시엔_한국_17시에_아직_열리지_않는다(self, monkeypatch, 평일):
        지금(monkeypatch, datetime(2026, 11, 5, 17, 30))

        assert calendar.us_market_status() == (False, False)

    def test_표준시엔_한국_18시에_열린다(self, monkeypatch, 평일):
        지금(monkeypatch, datetime(2026, 11, 5, 18, 0))

        assert calendar.us_market_status() == (False, True)


class Test다음_미국장까지_남은_시간:
    def test_한국_낮이면_그날_저녁_프리마켓까지다(self, monkeypatch):
        지금(monkeypatch, datetime(2026, 9, 25, 10, 0))

        assert calendar.seconds_until_us_session() == 7 * 3600

    def test_애프터마켓이_끝난_아침이면_그날_저녁까지다(self, monkeypatch):
        지금(monkeypatch, datetime(2026, 9, 26, 9, 30))

        assert calendar.seconds_until_us_session() == 7.5 * 3600

    def test_서머타임이_끝나는_밤을_넘기면_한_시간_더_기다린다(self, monkeypatch):
        """2026-11-01 새벽에 서머타임이 끝난다 — 벽시계로 빼면 한 시간이 사라진다."""
        지금(monkeypatch, datetime(2026, 10, 31, 20, 0))   # 미 동부 07:00 EDT

        # 다음 04:00 EST는 한국 11/1 18:00 — 22시간 뒤
        assert calendar.seconds_until_us_session() == 22 * 3600


class Test직전_개장일:
    """주말만 건너뛰면 추석 같은 연휴에 장이 안 열린 날을 거래일로 적는다 — 개장일 목록으로 짚는다."""

    @staticmethod
    def 개장일(monkeypatch, *days: date) -> None:
        monkeypatch.setattr(calendar, "_refreshed_open_days", lambda: frozenset(days))

    def test_연휴를_건너뛰어_마지막으로_연_날을_준다(self, monkeypatch):
        # 2026 추석 — 9/24(목)~9/25(금) 휴장, 9/23(수)이 마지막 개장일
        self.개장일(monkeypatch, date(2026, 9, 22), date(2026, 9, 23), date(2026, 9, 28))

        assert calendar.previous_open_day(date(2026, 9, 26)) == date(2026, 9, 23)

    def test_그날은_넣지_않는다(self, monkeypatch):
        """오늘이 개장일이어도 '직전'은 어제 이전이다 — 오늘을 쓸지는 화면이 시각으로 정한다."""
        self.개장일(monkeypatch, date(2026, 9, 22), date(2026, 9, 23))

        assert calendar.previous_open_day(date(2026, 9, 23)) == date(2026, 9, 22)

    def test_목록을_못_받았으면_모른다고_한다(self, monkeypatch):
        """빈 목록으로 날짜를 지어내면 틀린 날을 확신 있게 적는다 — 화면이 주말 폴백을 쓰게 둔다."""
        self.개장일(monkeypatch)

        assert calendar.previous_open_day(date(2026, 9, 26)) is None


class Test휴장_조회:
    """토스 장 운영 정보는 초당 3건 — 몰려도 한 번만 묻고, 실패는 오래 들고 있지 않는다."""

    @pytest.fixture
    def 토스(self, monkeypatch):
        calendar.reset()
        monkeypatch.setattr(calendar.Region, "today", lambda self: date(2026, 9, 24))   # 목요일, 추석
        상태 = {"응답": False, "호출": 0}

        def 조회(region, on):
            상태["호출"] += 1
            time.sleep(0.05)   # 뒤따라온 스레드가 진행 중인 조회를 보게 한다
            return 상태["응답"]

        monkeypatch.setattr(calendar.toss_calendar, "is_trading_day", 조회)
        yield 상태
        calendar.reset()

    def test_동시에_몰려도_토스에는_한_번만_묻는다(self, 토스):
        출발선 = threading.Barrier(5)

        def 묻기():
            출발선.wait()
            return calendar.is_holiday(calendar.Region.KR)

        with ThreadPoolExecutor(max_workers=5) as pool:
            결과들 = [f.result() for f in [pool.submit(묻기) for _ in range(5)]]

        assert 토스["호출"] == 1
        assert 결과들 == [True] * 5

    def test_성공한_답은_그날_내내_다시_묻지_않는다(self, 토스):
        calendar.is_holiday(calendar.Region.KR)
        calendar.is_holiday(calendar.Region.KR)

        assert 토스["호출"] == 1

    def test_실패하면_주말로만_판정하고_잠시_뒤_다시_묻는다(self, 토스, monkeypatch):
        """평일 휴장(추석)을 하루 종일 개장으로 보면 안 된다."""
        토스["응답"] = None
        assert calendar.is_holiday(calendar.Region.KR) is False       # 목요일이라 주말 폴백은 개장
        assert calendar.is_holiday(calendar.Region.KR) is False
        assert 토스["호출"] == 1                                       # 재시도 전에는 다시 묻지 않는다

        지금 = calendar.monotonic()
        monkeypatch.setattr(calendar, "monotonic", lambda: 지금 + calendar._RETRY_AFTER_FAILURE_SECONDS + 1)
        토스["응답"] = False

        assert calendar.is_holiday(calendar.Region.KR) is True
        assert 토스["호출"] == 2
