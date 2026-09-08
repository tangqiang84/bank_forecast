from __future__ import annotations

from uuid import uuid4

from pydantic import BaseModel


class ApiResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: object | None = None
    traceId: str = ""


def ok(data: object) -> ApiResponse:
    return ApiResponse(code=0, message="ok", data=data, traceId=uuid4().hex)


def fail(code: int, message: str, data: object | None = None) -> ApiResponse:
    return ApiResponse(code=code, message=message, data=data, traceId=uuid4().hex)
