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

    @classmethod
    def created(cls, data: T) -> "ApiResponse[T]":
        """**HTTP 상태는 200 그대로**다. Kotlin 컨트롤러도 `@ResponseStatus` 없이 이 객체만 돌려주므로
        201은 봉투 안 `status` 필드에만 나타난다. 프론트가 그 모양을 기대한다."""
        return cls(code="SUCCESS", status=201, data=data)

    @classmethod
    def accepted(cls) -> "ApiResponse[None]":
        return cls(code="SUCCESS", status=202, data=None)
