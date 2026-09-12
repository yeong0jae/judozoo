"""관심 테마 + 담긴 종목의 순서 관리."""

import pytest

from backend.library.db import get_engine, get_session_factory
from backend.watchlist import application as watch_app
from backend.watchlist.domain import WatchTheme, WatchThemeStock


@pytest.fixture
def 빈_테이블(통합_db):
    engine = get_engine()
    for model in (WatchTheme, WatchThemeStock):
        model.__table__.create(engine, checkfirst=True)
    with get_session_factory()() as s:
        s.query(WatchThemeStock).delete()
        s.query(WatchTheme).delete()
        s.commit()
    yield


class Test관심_테마:
    def test_만들고_목록에서_읽는다(self, 빈_테이블):
        with get_session_factory()() as s:
            watch_app.create_theme(s, "반도체")

            assert [t.name for t in watch_app.find_all(s)] == ["반도체"]

    def test_이름이_겹치면_거부한다(self, 빈_테이블):
        with get_session_factory()() as s:
            watch_app.create_theme(s, "반도체")

            with pytest.raises(ValueError, match="이미 있는"):
                watch_app.create_theme(s, "반도체")

    def test_만든_순서대로_정렬된다(self, 빈_테이블):
        with get_session_factory()() as s:
            for 이름 in ["첫째", "둘째", "셋째"]:
                watch_app.create_theme(s, 이름)

            assert [t.name for t in watch_app.find_all(s)] == ["첫째", "둘째", "셋째"]

    def test_테마_순서를_바꾼다(self, 빈_테이블):
        with get_session_factory()() as s:
            ids = [watch_app.create_theme(s, n).id for n in ["A", "B", "C"]]

            watch_app.reorder_themes(s, [ids[2], ids[0], ids[1]])

            assert [t.name for t in watch_app.find_all(s)] == ["C", "A", "B"]

    def test_종목을_추가한_순서대로_보여준다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")

            watch_app.add_stock(s, 테마.id, "005930", "삼성전자", None)
            결과 = watch_app.add_stock(s, 테마.id, "000660", "SK하이닉스", None)

            assert [x.stock_code for x in 결과.stocks] == ["005930", "000660"]

    def test_같은_종목을_다시_담아도_늘지_않는다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")
            watch_app.add_stock(s, 테마.id, "005930", "삼성전자", None)

            결과 = watch_app.add_stock(s, 테마.id, "005930", "삼성전자", None)

            assert len(결과.stocks) == 1

    def test_같은_코드를_다른_거래소로_담으면_DB_제약에_걸린다(self, 빈_테이블):
        """**Kotlin에도 있는 잠재 결함을 그대로 옮겼다.**

        중복 판정은 (코드, 거래소)로 하는데 유니크 제약은 (theme_id, stock_code)뿐이라,
        메모리 검사는 통과하고 INSERT에서 터진다. 순수 이관이라 같게 뒀다 —
        고치려면 제약과 판정을 함께 바꿔야 해서 양쪽 백엔드를 동시에 손대야 한다.
        """
        from sqlalchemy.exc import IntegrityError

        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "혼합")
            watch_app.add_stock(s, 테마.id, "AAPL", "애플", None)

            with pytest.raises(IntegrityError):
                watch_app.add_stock(s, 테마.id, "AAPL", "애플", "NAS")

    def test_종목_순서를_바꾼다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")
            for 코드, 이름 in [("A", "가"), ("B", "나"), ("C", "다")]:
                watch_app.add_stock(s, 테마.id, 코드, 이름, None)

            결과 = watch_app.reorder_stocks(s, 테마.id, ["C", "A", "B"])

            assert [x.stock_code for x in 결과.stocks] == ["C", "A", "B"]

    def test_순서_목록에_없는_종목은_뒤에_남는다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")
            for 코드 in ["A", "B", "C"]:
                watch_app.add_stock(s, 테마.id, 코드, 코드, None)

            결과 = watch_app.reorder_stocks(s, 테마.id, ["C"])

            assert 결과.stocks[0].stock_code == "C"
            assert {x.stock_code for x in 결과.stocks[1:]} == {"A", "B"}

    def test_종목을_뺀다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")
            watch_app.add_stock(s, 테마.id, "005930", "삼성전자", None)

            결과 = watch_app.remove_stock(s, 테마.id, "005930")

            assert 결과.stocks == []

    def test_테마를_지우면_담긴_종목도_같이_사라진다(self, 빈_테이블):
        with get_session_factory()() as s:
            테마 = watch_app.create_theme(s, "반도체")
            watch_app.add_stock(s, 테마.id, "005930", "삼성전자", None)

            watch_app.delete_theme(s, 테마.id)

            assert watch_app.find_all(s) == []
            assert s.query(WatchThemeStock).count() == 0

    def test_없는_테마에_담으면_거부한다(self, 빈_테이블):
        with get_session_factory()() as s, pytest.raises(ValueError, match="없는 테마"):
            watch_app.add_stock(s, 99999, "005930", "삼성전자", None)
