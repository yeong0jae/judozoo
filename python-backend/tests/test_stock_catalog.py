"""종목 카탈로그 갱신 — 다운로드 생략 / 전체 교체 / 실패 폴백.

테이블은 이관 기간에 Kotlin(`ddl-auto=update`)이 소유한다. 여기서만 메타데이터로 만든다.
"""

from datetime import date, datetime, timedelta

import pytest
from sqlalchemy import select

from backend.library.db import get_engine, get_session_factory
from backend.stock import application
from backend.stock.domain import Market, OverseasStock, Stock

오늘 = date(2026, 9, 12)
어제 = 오늘 - timedelta(days=1)


def 종목(code: str, name: str, at: datetime) -> Stock:
    return Stock(
        short_code=code, standard_code=f"KR7{code}", name=name,
        market=Market.KOSPI, created_at=at, updated_at=at,
    )


def 해외종목(symbol: str, at: datetime) -> OverseasStock:
    return OverseasStock(
        exchange="NAS", symbol=symbol, name=symbol, english_name=symbol,
        created_at=at, updated_at=at,
    )


@pytest.fixture
def 빈_카탈로그_테이블(통합_db):
    """두 테이블만 만들고 매 테스트마다 비운다."""
    engine = get_engine()
    for model in (Stock, OverseasStock):
        model.__table__.create(engine, checkfirst=True)
    with get_session_factory()() as session:
        session.query(Stock).delete()
        session.query(OverseasStock).delete()
        session.commit()
    application._domestic = application.Stocks([])
    application._overseas = []
    yield


@pytest.fixture
def 다운로드_금지(monkeypatch):
    """호출되면 실패한다 — 다운로드를 건너뛰는지 검증하는 데 쓴다."""
    def 폭발(*_args, **_kwargs):
        raise AssertionError("다운로드하면 안 된다")

    monkeypatch.setattr(application.infrastructure, "fetch_domestic", 폭발)
    monkeypatch.setattr(application.infrastructure, "fetch_overseas", 폭발)


class Test카탈로그_갱신:
    def test_오늘_이미_동기화됐으면_다운로드하지_않고_DB에서_적재한다(
        self, 빈_카탈로그_테이블, 다운로드_금지
    ):
        with get_session_factory()() as session:
            session.add(종목("005930", "삼성전자", datetime(2026, 9, 12, 8, 30)))
            session.add(해외종목("AAPL", datetime(2026, 9, 12, 8, 30)))
            session.commit()

        application.refresh(오늘)

        assert application.catalog_sizes() == (1, 1)
        assert application.search("삼성")[0].stock_code == "005930"

    def test_동기화가_어제_것이면_새로_받아_전체_교체한다(
        self, 빈_카탈로그_테이블, monkeypatch
    ):
        with get_session_factory()() as session:
            session.add(종목("000000", "옛날종목", datetime(2026, 9, 11, 8, 30)))
            session.commit()

        새것 = datetime(2026, 9, 12, 8, 30)
        monkeypatch.setattr(
            application.infrastructure, "fetch_domestic",
            lambda: [종목("005930", "삼성전자", 새것)],
        )
        monkeypatch.setattr(application.infrastructure, "fetch_overseas", lambda: [])

        application.refresh(오늘)

        with get_session_factory()() as session:
            남은것 = list(session.scalars(select(Stock.short_code)))
        assert 남은것 == ["005930"]  # 옛날종목은 사라진다
        assert application.catalog_sizes()[0] == 1

    def test_다운로드가_실패하면_DB의_직전_데이터로_폴백한다(
        self, 빈_카탈로그_테이블, monkeypatch
    ):
        with get_session_factory()() as session:
            session.add(종목("005930", "삼성전자", datetime(2026, 9, 11, 8, 30)))
            session.commit()

        def 실패():
            raise RuntimeError("CDN 죽음")

        monkeypatch.setattr(application.infrastructure, "fetch_domestic", 실패)
        monkeypatch.setattr(application.infrastructure, "fetch_overseas", lambda: [])

        application.refresh(오늘)  # 예외가 밖으로 나오지 않는다

        assert application.catalog_sizes()[0] == 1
        assert application.search("삼성")[0].stock_name == "삼성전자"

    def test_다운로드도_DB도_비어있으면_빈_카탈로그로_남는다(
        self, 빈_카탈로그_테이블, monkeypatch
    ):
        def 실패():
            raise RuntimeError("CDN 죽음")

        monkeypatch.setattr(application.infrastructure, "fetch_domestic", 실패)
        monkeypatch.setattr(application.infrastructure, "fetch_overseas", 실패)

        application.refresh(오늘)

        assert application.catalog_sizes() == (0, 0)
        assert application.search("삼성") == []
