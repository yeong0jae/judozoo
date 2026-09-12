"""이슈 메모 CRUD."""

from datetime import date, datetime

import pytest

from backend.issue import application as issue_app
from backend.issue.domain import DailyIssue
from backend.library.db import get_engine, get_session_factory
from backend.library.exception import EntityNotFoundError

오늘 = date(2026, 9, 11)


@pytest.fixture
def 빈_테이블(통합_db):
    DailyIssue.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as s:
        s.query(DailyIssue).delete()
        s.commit()
    yield


class Test이슈_메모:
    def test_작성하고_그날_목록에서_읽는다(self, 빈_테이블):
        with get_session_factory()() as s:
            issue_app.add(s, 오늘, "반도체 강세")

            목록 = issue_app.issues_on(s, 오늘)

        assert [i.content for i in 목록] == ["반도체 강세"]

    def test_앞뒤_공백은_잘라서_저장한다(self, 빈_테이블):
        with get_session_factory()() as s:
            issue_app.add(s, 오늘, "  공백 있음  ")

            assert issue_app.issues_on(s, 오늘)[0].content == "공백 있음"

    def test_빈_내용은_거부한다(self, 빈_테이블):
        with get_session_factory()() as s, pytest.raises(ValueError, match="비어 있"):
            issue_app.add(s, 오늘, "   ")

    def test_입력_순으로_돌려준다(self, 빈_테이블):
        with get_session_factory()() as s:
            for i, 내용 in enumerate(["첫째", "둘째", "셋째"]):
                issue_app.add(s, 오늘, 내용)
                # created_at이 같은 값이면 순서가 흔들리므로 벌려 둔다
                s.query(DailyIssue).filter_by(content=내용).update(
                    {"created_at": datetime(2026, 9, 11, 9, i)}
                )
                s.commit()

            assert [i.content for i in issue_app.issues_on(s, 오늘)] == ["첫째", "둘째", "셋째"]

    def test_다른_날짜는_섞이지_않는다(self, 빈_테이블):
        with get_session_factory()() as s:
            issue_app.add(s, 오늘, "오늘 것")
            issue_app.add(s, date(2026, 9, 10), "어제 것")

            assert [i.content for i in issue_app.issues_on(s, 오늘)] == ["오늘 것"]

    def test_수정한다(self, 빈_테이블):
        with get_session_factory()() as s:
            만든것 = issue_app.add(s, 오늘, "원본")

            issue_app.edit(s, 만든것.id, "수정본")

            assert issue_app.issues_on(s, 오늘)[0].content == "수정본"

    def test_없는_이슈_수정은_찾을_수_없음(self, 빈_테이블):
        with get_session_factory()() as s, pytest.raises(EntityNotFoundError):
            issue_app.edit(s, 99999, "아무거나")

    def test_삭제한다(self, 빈_테이블):
        with get_session_factory()() as s:
            만든것 = issue_app.add(s, 오늘, "지울 것")

            issue_app.delete(s, 만든것.id)

            assert issue_app.issues_on(s, 오늘) == []

    def test_없는_이슈_삭제는_찾을_수_없음(self, 빈_테이블):
        with get_session_factory()() as s, pytest.raises(EntityNotFoundError):
            issue_app.delete(s, 99999)
