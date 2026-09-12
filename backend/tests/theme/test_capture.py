"""테마 캡처 — 적재 날짜 판정과 집계."""

from datetime import date, datetime

import pytest

from backend.library.db import get_engine, get_session_factory
from backend.theme import application
from backend.theme.domain import ThemeDailyRecord, ThemeDailyStock


class Test적재_날짜_판정:
    """전일 종가 기준 거래대금이 당일 날짜로 잘못 들어가면 캘린더가 하루씩 밀린다."""

    def test_평일_장중이면_당일로_적재한다(self):
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 11, 15, 0)
        ) == date(2026, 9, 11)

    def test_평일_프리마켓_시작_직후면_당일(self):
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 11, 8, 0)
        ) == date(2026, 9, 11)

    def test_평일이라도_개장_전_새벽이면_직전_거래일(self):
        """아직 당일 데이터가 없다 — 금요일(9/11) 새벽 → 목요일(9/10)."""
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 11, 3, 0)
        ) == date(2026, 9, 10)

    def test_월요일_새벽이면_금요일로_거슬러_간다(self):
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 14, 3, 0)
        ) == date(2026, 9, 11)

    def test_주말이면_직전_금요일(self):
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 12, 15, 0)
        ) == date(2026, 9, 11)
        assert application._most_recent_trading_day_with_data(
            datetime(2026, 9, 13, 15, 0)
        ) == date(2026, 9, 11)


AT = datetime(2026, 9, 11, 15, 40)


@pytest.fixture
def 빈_테마_테이블(통합_db):
    engine = get_engine()
    for model in (ThemeDailyRecord, ThemeDailyStock):
        model.__table__.create(engine, checkfirst=True)
    with get_session_factory()() as s:
        s.query(ThemeDailyStock).delete()
        s.query(ThemeDailyRecord).delete()
        s.commit()
    yield


def 스냅샷(code, name, 거래대금, 등락률=5.0):
    from backend.leadingstock.domain import LeadingStockSnapshot

    return LeadingStockSnapshot(
        stock_code=code, stock_name=name, current_price=1000, price_change_rate=등락률,
        trading_value_rank=1, accumulated_trading_value=거래대금,
    )


@pytest.fixture
def 키움_대역(monkeypatch):
    def 설정(종목들, 테마맵):
        monkeypatch.setattr(
            "backend.platform.kiwoom.market.fetch_top_trading_value_stocks",
            lambda count=50: 종목들,
        )
        monkeypatch.setattr(
            "backend.platform.kiwoom.theme.fetch_themes_for_stock",
            lambda code: 테마맵.get(code, []),
        )
    return 설정


class Test캡처:
    def test_거래대금을_테마별로_합산해_순위를_매긴다(self, 빈_테마_테이블, 키움_대역):
        키움_대역(
            [스냅샷("005930", "삼성전자", 300), 스냅샷("000660", "SK하이닉스", 200),
             스냅샷("035720", "카카오", 400)],
            {"005930": ["반도체"], "000660": ["반도체"], "035720": ["플랫폼"]},
        )

        with get_session_factory()() as s:
            저장수 = application.capture(s, AT)
            기록 = list(s.query(ThemeDailyRecord).order_by(ThemeDailyRecord.rank))

        assert 저장수 == 2
        assert [(r.rank, r.theme_name, r.trading_value) for r in 기록] == [
            (1, "반도체", 500),   # 300 + 200
            (2, "플랫폼", 400),
        ]

    def test_기여_종목을_거래대금_내림차순으로_붙인다(self, 빈_테마_테이블, 키움_대역):
        키움_대역(
            [스냅샷("005930", "삼성전자", 300), 스냅샷("000660", "SK하이닉스", 500)],
            {"005930": ["반도체"], "000660": ["반도체"]},
        )

        with get_session_factory()() as s:
            application.capture(s, AT)
            종목들 = list(
                s.query(ThemeDailyStock).order_by(ThemeDailyStock.trading_value.desc())
            )

        assert [x.stock_name for x in 종목들] == ["SK하이닉스", "삼성전자"]
        assert 종목들[0].price_change_rate == 5.0

    def test_거래대금이_0이하인_종목은_집계에서_뺀다(self, 빈_테마_테이블, 키움_대역):
        키움_대역([스냅샷("005930", "삼성전자", 0)], {"005930": ["반도체"]})

        with get_session_factory()() as s:
            assert application.capture(s, AT) == 0

    def test_집계할_테마가_없으면_저장하지_않는다(self, 빈_테마_테이블, 키움_대역):
        키움_대역([스냅샷("005930", "삼성전자", 300)], {})  # 테마 없음

        with get_session_factory()() as s:
            assert application.capture(s, AT) == 0
            assert s.query(ThemeDailyRecord).count() == 0

    def test_같은_날_다시_캡처하면_교체한다(self, 빈_테마_테이블, 키움_대역):
        """15:40과 20:00 두 번 도는데, 유니크(date, theme_name)라 삭제 후 재적재해야 한다."""
        키움_대역([스냅샷("005930", "삼성전자", 300)], {"005930": ["반도체"]})
        with get_session_factory()() as s:
            application.capture(s, AT)

        키움_대역([스냅샷("005930", "삼성전자", 900)], {"005930": ["반도체"]})
        with get_session_factory()() as s:
            application.capture(s, AT)
            기록 = list(s.query(ThemeDailyRecord))

        assert len(기록) == 1
        assert 기록[0].trading_value == 900

    def test_상위_12개까지만_저장한다(self, 빈_테마_테이블, 키움_대역):
        종목들 = [스냅샷(f"{i:06d}", f"종목{i}", (20 - i) * 100) for i in range(15)]
        키움_대역(종목들, {f"{i:06d}": [f"테마{i}"] for i in range(15)})

        with get_session_factory()() as s:
            assert application.capture(s, AT) == 12
