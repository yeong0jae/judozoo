"""거래일별 이슈 메모 CRUD. 타임라인(이슈 화면)이 조회·작성·수정·삭제에 쓴다."""

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.issue.domain import DailyIssue
from backend.library.exception import EntityNotFoundError
from backend.library.time import now


def issues_on(session: Session, on: date) -> list[DailyIssue]:
    """그 거래일의 이슈를 입력 순(오래된→최신)으로."""
    return list(
        session.scalars(
            select(DailyIssue)
            .where(DailyIssue.trade_date == on)
            .order_by(DailyIssue.created_at.asc())
        )
    )


def add(session: Session, on: date, content: str) -> DailyIssue:
    trimmed = _require_content(content)
    at = now()
    issue = DailyIssue(trade_date=on, content=trimmed, created_at=at, updated_at=at)
    session.add(issue)
    session.commit()
    return issue


def edit(session: Session, issue_id: int, content: str) -> DailyIssue:
    trimmed = _require_content(content)
    issue = session.get(DailyIssue, issue_id)
    if issue is None:
        raise EntityNotFoundError(f"이슈 없음: {issue_id}")
    issue.edit(trimmed)
    issue.updated_at = now()
    session.commit()
    return issue


def delete(session: Session, issue_id: int) -> None:
    issue = session.get(DailyIssue, issue_id)
    if issue is None:
        raise EntityNotFoundError(f"이슈 없음: {issue_id}")
    session.delete(issue)
    session.commit()


def _require_content(content: str) -> str:
    trimmed = (content or "").strip()
    if not trimmed:
        # Kotlin의 require(...)에 대응 — 전용 핸들러가 없어 500으로 떨어진다. 순수 이관이라 같게 둔다.
        raise ValueError("이슈 내용이 비어 있습니다")
    return trimmed
