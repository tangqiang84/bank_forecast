from __future__ import annotations

from contextvars import ContextVar
from uuid import uuid4

from pydantic import BaseModel


_trace_id: ContextVar[str | None] = ContextVar("trace_id", default=None)


class ApiResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: object | None = None
    trace_id: str = ""


def current_trace_id() -> str:
    trace_id = _trace_id.get()
    if trace_id:
        return trace_id
    trace_id = uuid4().hex
    _trace_id.set(trace_id)
    return trace_id


def set_trace_id(trace_id: str | None) -> None:
    _trace_id.set(trace_id.strip() if trace_id and trace_id.strip() else uuid4().hex)


def ok(data: object) -> ApiResponse:
    return ApiResponse(code=0, message="ok", data=data, trace_id=current_trace_id())


def fail(code: int, message: str, data: object | None = None) -> ApiResponse:
    return ApiResponse(code=code, message=message, data=data, trace_id=current_trace_id())
