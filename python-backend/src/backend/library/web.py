"""API 응답 봉투.

Kotlin `library.web.ApiResponse`와 **필드 이름·순서까지** 같아야 한다.
프론트가 `data`만 꺼내 쓰므로 봉투가 달라지면 화면 전체가 깨진다.
"""

from typing import Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    code: str
    status: int
    data: T | None = None

    @classmethod
    def ok(cls, data: T) -> "ApiResponse[T]":
        return cls(code="SUCCESS", status=200, data=data)
