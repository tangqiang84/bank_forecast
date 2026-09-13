import asyncio
import json
import time

from fastapi.testclient import TestClient

from bank_forecast_analytics.app import _reload_enabled, app, create_app


def test_health() -> None:
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    assert body["data"]["status"] == "UP"
    assert body["trace_id"]
    assert "traceId" not in body


def test_forecast() -> None:
    client = TestClient(app)

    response = client.post(
        "/forecast/cashflow",
        json={"history": [1, 2, 3], "horizon": 2, "window_size": 2},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    assert body["data"]["method"] == "moving-average-with-trend"
    assert body["data"]["model_version"] == "v1"
    assert len(body["data"]["forecast_values"]) == 2
    assert body["trace_id"]
    assert "traceId" not in body


def test_forecast_uses_backend_trace_id() -> None:
    client = TestClient(app)

    response = client.post(
        "/forecast/cashflow",
        headers={"X-Trace-Id": "backend-trace-1"},
        json={"history": [1, 2, 3], "horizon": 2, "window_size": 2},
    )

    assert response.status_code == 200
    assert response.json()["trace_id"] == "backend-trace-1"


def test_validation_error_uses_structured_response() -> None:
    client = TestClient(app)

    response = client.post("/forecast/cashflow", json={"history": [], "horizon": 2, "window_size": 2})

    body = response.json()
    assert response.status_code == 422
    assert body["code"] == 40001
    assert body["message"] == "参数校验失败"
    assert body["trace_id"]
    assert body["data"]["errors"]


def test_rejects_nan_and_infinity() -> None:
    client = TestClient(app)

    for raw_value in ["NaN", "Infinity"]:
        response = client.post(
            "/forecast/cashflow",
            content=f'{{"history":[1,{raw_value}],"horizon":2,"window_size":2}}',
            headers={"content-type": "application/json"},
        )
        body = response.json()
        assert response.status_code == 422
        assert body["code"] == 40001


def test_rejects_history_over_limit(monkeypatch) -> None:
    monkeypatch.setenv("ANALYTICS_HISTORY_MAX_LENGTH", "2")
    client = TestClient(app)

    response = client.post("/forecast/cashflow", json={"history": [1, 2, 3], "horizon": 2, "window_size": 2})

    assert response.status_code == 422
    assert response.json()["code"] == 40001


def test_rejects_window_size_larger_than_history() -> None:
    client = TestClient(app)

    response = client.post("/forecast/cashflow", json={"history": [1, 2], "horizon": 2, "window_size": 3})

    assert response.status_code == 422
    assert response.json()["code"] == 40001


def test_rejects_unknown_model_version() -> None:
    client = TestClient(app)

    response = client.post(
        "/forecast/cashflow",
        json={"history": [1, 2, 3], "horizon": 2, "window_size": 2, "model_version": "missing"},
    )

    assert response.status_code == 422
    assert response.json()["code"] == 40001


def test_rejects_inactive_model_version(monkeypatch) -> None:
    from bank_forecast_analytics import models

    original = models.MODEL_REGISTRY["v1"].copy()
    monkeypatch.setitem(models.MODEL_REGISTRY["v1"], "status", "inactive")
    client = TestClient(app)

    response = client.post("/forecast/cashflow", json={"history": [1, 2, 3], "horizon": 2, "window_size": 2})

    assert response.status_code == 422
    assert response.json()["code"] == 40001
    models.MODEL_REGISTRY["v1"].update(original)


def test_request_body_size_limit(monkeypatch) -> None:
    monkeypatch.setenv("ANALYTICS_MAX_REQUEST_BODY_BYTES", "10")
    limited_app = create_app()
    client = TestClient(limited_app)

    response = client.post("/forecast/cashflow", json={"history": [1, 2, 3], "horizon": 2, "window_size": 2})

    assert response.status_code == 413
    assert response.json()["code"] == 40002


def test_request_body_size_limit_without_content_length(monkeypatch) -> None:
    monkeypatch.setenv("ANALYTICS_MAX_REQUEST_BODY_BYTES", "10")
    limited_app = create_app()
    messages = [
        {"type": "http.request", "body": b'{"history"', "more_body": True},
        {"type": "http.request", "body": b":[1,2,3]}"},
    ]
    sent_messages = []

    async def run_request() -> None:
        async def receive():
            return messages.pop(0)

        async def send(message):
            sent_messages.append(message)

        await limited_app(
            {
                "type": "http",
                "asgi": {"version": "3.0"},
                "http_version": "1.1",
                "method": "POST",
                "scheme": "http",
                "path": "/forecast/cashflow",
                "raw_path": b"/forecast/cashflow",
                "query_string": b"",
                "headers": [(b"content-type", b"application/json")],
                "client": ("testclient", 50000),
                "server": ("testserver", 80),
            },
            receive,
            send,
        )

    asyncio.run(run_request())

    start = next(message for message in sent_messages if message["type"] == "http.response.start")
    body = b"".join(message.get("body", b"") for message in sent_messages if message["type"] == "http.response.body")
    assert start["status"] == 413
    assert json.loads(body)["code"] == 40002


def test_invalid_content_length_uses_structured_error() -> None:
    client = TestClient(app)

    response = client.post("/forecast/cashflow", content="{}", headers={"content-length": "invalid"})

    assert response.status_code == 400
    assert response.json()["code"] == 40001


def test_request_timeout(monkeypatch) -> None:
    import bank_forecast_analytics.app as app_module

    monkeypatch.setenv("ANALYTICS_REQUEST_TIMEOUT_SECONDS", "0.01")
    original_forecast = app_module.forecast_cashflow

    def slow_forecast(request):
        time.sleep(0.05)
        return original_forecast(request)

    monkeypatch.setattr(app_module, "forecast_cashflow", slow_forecast)
    timeout_app = create_app()
    client = TestClient(timeout_app)

    response = client.post("/forecast/cashflow", json={"history": [1, 2, 3], "horizon": 2, "window_size": 2})

    assert response.status_code == 504
    assert response.json()["code"] == 50004


def test_concurrency_limit() -> None:
    client = TestClient(app)
    app.state.active_requests = app.state.max_concurrent_requests

    response = client.get("/health")

    app.state.active_requests = 0

    assert response.status_code == 429
    assert response.json()["code"] == 50005


def test_models() -> None:
    client = TestClient(app)
    response = client.get("/models")
    assert response.status_code == 200
    body = response.json()
    assert body["data"][0]["version"] == "v1"
    assert body["trace_id"]
    assert "traceId" not in body


def test_reload_is_enabled_only_when_explicitly_configured(monkeypatch) -> None:
    monkeypatch.setenv("APP_ENV", "local")
    monkeypatch.setenv("ANALYTICS_RELOAD", "true")
    assert _reload_enabled() is True

    monkeypatch.setenv("APP_ENV", "production")
    assert _reload_enabled() is False


def test_reload_is_disabled_by_default(monkeypatch) -> None:
    monkeypatch.delenv("APP_ENV", raising=False)
    monkeypatch.delenv("ANALYTICS_RELOAD", raising=False)
    assert _reload_enabled() is False
