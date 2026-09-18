"""가입자 기록 — 실제 MySQL에서 대체 키가 도는지 본다.

`app_user`의 키를 `google_sub`에서 내부 `id`로 옮겼다(V006). 자동 증가 키는
SQLAlchemy가 INSERT 뒤에 값을 되받아야 세션에 실을 수 있어서, SQLite나 목으로는
확인이 안 된다.
"""

from datetime import datetime

import pytest

from backend.auth import application
from backend.auth.domain import AppUser, CurrentUser
from backend.library import db

pytestmark = pytest.mark.integration


@pytest.fixture
def 빈_가입자_테이블(통합_db):
    AppUser.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(AppUser).delete()
        s.commit()
    yield


def 구글_사용자(sub="108111111111111111111", email="a@example.com") -> CurrentUser:
    return CurrentUser(google_sub=sub, email=email)


class Test첫_로그인:
    def test_내부_식별자를_발급해_돌려준다(self, 빈_가입자_테이블):
        기록된 = application.record_login(
            db.get_session_factory()(), 구글_사용자(), datetime(2026, 9, 18, 20, 0)
        )

        assert 기록된.id is not None
        assert 기록된.google_sub == "108111111111111111111"

    def test_사람마다_다른_식별자를_받는다(self, 빈_가입자_테이블):
        세션 = db.get_session_factory()()
        때 = datetime(2026, 9, 18, 20, 0)

        갑 = application.record_login(세션, 구글_사용자("111", "gap@example.com"), 때)
        을 = application.record_login(세션, 구글_사용자("222", "eul@example.com"), 때)

        assert 갑.id != 을.id


class Test재방문:
    def test_같은_사람은_식별자가_바뀌지_않는다(self, 빈_가입자_테이블):
        """식별자가 매번 바뀌면 로그를 이어 붙일 수 없다."""
        세션 = db.get_session_factory()()

        처음 = application.record_login(세션, 구글_사용자(), datetime(2026, 9, 18, 20, 0))
        다시 = application.record_login(세션, 구글_사용자(), datetime(2026, 9, 19, 9, 0))

        assert 다시.id == 처음.id

    def test_가입자가_늘지_않는다(self, 빈_가입자_테이블):
        세션 = db.get_session_factory()()
        application.record_login(세션, 구글_사용자(), datetime(2026, 9, 18, 20, 0))
        application.record_login(세션, 구글_사용자(), datetime(2026, 9, 19, 9, 0))

        assert 세션.query(AppUser).count() == 1

    def test_바뀐_이메일을_따라간다(self, 빈_가입자_테이블):
        세션 = db.get_session_factory()()
        application.record_login(세션, 구글_사용자(email="old@example.com"), datetime(2026, 9, 18, 20, 0))

        application.record_login(세션, 구글_사용자(email="new@example.com"), datetime(2026, 9, 19, 9, 0))

        assert 세션.query(AppUser).one().email == "new@example.com"
