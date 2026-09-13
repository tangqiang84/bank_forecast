from __future__ import annotations

import asyncio
import os
from datetime import datetime, timezone

import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .common import fail, ok, set_trace_id
from .forecast import forecast_cashflow
from .models import ForecastRequest, MODEL_REGISTRY


class RequestBodyTooLarge(Exception):
    pass


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


def _error_response(status_code: int, code: int, message: str, data: object | None = None) -> JSONResponse:
    return JSONResponse(status_code=status_code, content=fail(code, message, data).model_dump())


def create_app() -> FastAPI:
    app = FastAPI(title="bank_forecast analytics", version="0.1.0")
    app.state.max_request_body_bytes = _int_env("ANALYTICS_MAX_REQUEST_BODY_BYTES", 1_048_576)
    app.state.request_timeout_seconds = _float_env("ANALYTICS_REQUEST_TIMEOUT_SECONDS", 5.0)
    app.state.max_concurrent_requests = _int_env("ANALYTICS_MAX_CONCURRENT_REQUESTS", 8)
    app.state.active_requests = 0
    app.state.active_requests_lock = asyncio.Lock()
    allowed_origins = [
        origin.strip()
        for origin in os.getenv(
            "ANALYTICS_CORS_ALLOWED_ORIGINS",
            "http://127.0.0.1:5173,http://127.0.0.1:5174,http://localhost:5173,http://localhost:5174",
        ).split(",")
        if origin.strip()
    ]

    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.middleware("http")
    async def request_limits(request: Request, call_next):
        set_trace_id(request.headers.get("X-Trace-Id"))
        content_length = request.headers.get("content-length")
        try:
            request_size = int(content_length) if content_length else 0
        except ValueError:
            return _error_response(400, 40001, "Content-Length 非法")
        if request_size > app.state.max_request_body_bytes:
            return _error_response(413, 40002, "请求体超过大小限制", {"max_bytes": app.state.max_request_body_bytes})

        original_receive = request._receive
        body = bytearray()
        try:
            while True:
                message = await original_receive()
                if message.get("type") == "http.disconnect":
                    break
                body.extend(message.get("body", b""))
                if len(body) > app.state.max_request_body_bytes:
                    raise RequestBodyTooLarge()
                if not message.get("more_body", False):
                    break
        except RequestBodyTooLarge:
            return _error_response(413, 40002, "请求体超过大小限制", {"max_bytes": app.state.max_request_body_bytes})
        request._body = bytes(body)

        async with app.state.active_requests_lock:
            if app.state.active_requests >= app.state.max_concurrent_requests:
                return _error_response(429, 50005, "analytics 当前并发请求过多，请稍后重试")
            app.state.active_requests += 1
        try:
            return await asyncio.wait_for(call_next(request), timeout=app.state.request_timeout_seconds)
        except asyncio.TimeoutError:
            return _error_response(504, 50004, "analytics 请求处理超时")
        finally:
            async with app.state.active_requests_lock:
                app.state.active_requests -= 1

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        set_trace_id(request.headers.get("X-Trace-Id"))
        errors = [
            {"field": ".".join(str(part) for part in error.get("loc", [])), "reason": str(error.get("msg", "参数校验失败"))}
            for error in exc.errors()
        ]
        return _error_response(422, 40001, "参数校验失败", {"errors": errors})

    @app.get("/health")
    def health() -> object:
        return ok({
            "service": "bank-fund-connector-analytics",
            "status": "UP",
            "timestamp": datetime.now(timezone.utc).isoformat(),
        })

    @app.get("/forecast/sample")
    def sample() -> object:
        return ok({
            "history": [120000.0, 128000.0, 133000.0, 140000.0],
            "hint": "POST /forecast/cashflow",
        })

    @app.post("/forecast/cashflow")
    def cashflow(request: ForecastRequest):
        return ok(forecast_cashflow(request).model_dump())

    @app.get("/models")
    def models() -> object:
        return ok(list(MODEL_REGISTRY.values()))

    return app


app = create_app()


def _env_flag(value: str | None, default: bool = False) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _reload_enabled() -> bool:
    environment = os.getenv("APP_ENV", "local").strip().lower()
    if environment in {"prod", "production"}:
        return False
    return _env_flag(os.getenv("ANALYTICS_RELOAD"))


def main() -> None:
    host = os.getenv("ANALYTICS_HOST", "0.0.0.0")
    port = int(os.getenv("ANALYTICS_PORT", "8001"))
    uvicorn.run("bank_forecast_analytics.app:app", host=host, port=port, reload=_reload_enabled())


if __name__ == "__main__":
    main()
