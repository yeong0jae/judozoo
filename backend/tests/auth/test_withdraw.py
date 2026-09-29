"""탈퇴 — 가입자와 그 사람이 보낸 의견이 함께 사라지고, 남의 것은 남는다."""

from datetime import datetime

import pytest

from backend.auth import application
from backend.auth.domain import AppUser, CurrentUser
from backend.feedback.domain import Feedback
from backend.library import db

때 = datetime(2026, 9, 29, 17, 0)


class Test탈퇴_관문:
    def test_로그인하지_않으면_막힌다(self, client):
        응답 = client.post("/api/auth/withdraw")

        assert 응답.status_code == 401
        assert 응답.json()["code"] == "UNAUTHORIZED"

    def test_탈퇴하면_세션_쿠키를_비운다(self, 로그인_client, mocker):
        """쿠키가 Secure라 http 테스트 클라이언트는 새 쿠키를 받아 두지 않는다 — 응답 헤더로 본다."""
        mocker.patch.object(application, "withdraw")

        응답 = 로그인_client.post("/api/auth/withdraw")

        assert 응답.json()["status"] == 200
        assert 응답.headers["set-cookie"].startswith("judozoo_session=null;")


@pytest.fixture
def 빈_테이블(통합_db):
    for table in (AppUser.__table__, Feedback.__table__):
        table.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(Feedback).delete()
        s.query(AppUser).delete()
        s.commit()
    yield


def 가입(sub: str) -> CurrentUser:
    return application.record_login(db.get_session_factory()(), CurrentUser(google_sub=sub, email=f"{sub}@example.com"), 때)


def 의견(user: CurrentUser, content: str) -> None:
    with db.get_session_factory()() as s:
        s.add(Feedback.write(user.id, content, 때))
        s.commit()


@pytest.mark.integration
class Test탈퇴:
    def test_가입자와_그_사람의_의견을_함께_지운다(self, 빈_테이블):
        나 = 가입("111")
        의견(나, "차트가 느려요")
        의견(나, "관심 종목 기능이 있으면")

        application.withdraw(db.get_session_factory()(), 나)

        with db.get_session_factory()() as s:
            assert s.query(AppUser).count() == 0
            assert s.query(Feedback).count() == 0

    def test_다른_사람의_가입과_의견은_남는다(self, 빈_테이블):
        나, 남 = 가입("111"), 가입("222")
        의견(나, "내 의견")
        의견(남, "남의 의견")

        application.withdraw(db.get_session_factory()(), 나)

        with db.get_session_factory()() as s:
            assert [u.google_sub for u in s.query(AppUser)] == ["222"]
            assert [f.content for f in s.query(Feedback)] == ["남의 의견"]

    def test_내부_식별자가_없는_옛_세션도_탈퇴된다(self, 빈_테이블):
        가입("111")

        application.withdraw(db.get_session_factory()(), CurrentUser(google_sub="111", email="111@example.com"))

        with db.get_session_factory()() as s:
            assert s.query(AppUser).count() == 0

    def test_이미_탈퇴했으면_아무_일도_없다(self, 빈_테이블):
        나 = 가입("111")
        application.withdraw(db.get_session_factory()(), 나)

        application.withdraw(db.get_session_factory()(), 나)
