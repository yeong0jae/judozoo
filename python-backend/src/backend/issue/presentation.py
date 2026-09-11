"""거래일별 이슈 메모 API — 타임라인(이슈) 화면의 조회·작성·수정·삭제."""

from datetime import date

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from backend.issue import application
from backend.issue.domain import DailyIssue
from backend.library.db import get_db
from backend.library.web import ApiResponse

router = APIRouter(prefix="/api/issues")


class DailyIssueItem(BaseModel):
    id: int
    date: date
    content: str


class IssueCreateRequest(BaseModel):
    date: date
    content: str


class IssueUpdateRequest(BaseModel):
    content: str


def _to_item(issue: DailyIssue) -> DailyIssueItem:
    return DailyIssueItem(id=issue.id, date=issue.trade_date, content=issue.content)


@router.get("")
def list_issues(
    date_: date = Query(alias="date"), db: Session = Depends(get_db)
) -> ApiResponse[list[DailyIssueItem]]:
    return ApiResponse.ok([_to_item(i) for i in application.issues_on(db, date_)])


@router.post("")
def create(req: IssueCreateRequest, db: Session = Depends(get_db)) -> ApiResponse[DailyIssueItem]:
    return ApiResponse.created(_to_item(application.add(db, req.date, req.content)))


@router.put("/{issue_id}")
def update(
    issue_id: int, req: IssueUpdateRequest, db: Session = Depends(get_db)
) -> ApiResponse[DailyIssueItem]:
    return ApiResponse.ok(_to_item(application.edit(db, issue_id, req.content)))


@router.delete("/{issue_id}")
def delete(issue_id: int, db: Session = Depends(get_db)) -> ApiResponse[None]:
    application.delete(db, issue_id)
    return ApiResponse.accepted()
