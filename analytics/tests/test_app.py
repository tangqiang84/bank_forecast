from fastapi.testclient import TestClient

from bank_forecast_analytics.app import app


def test_health() -> None:
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    assert body["data"]["status"] == "UP"


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


def test_models() -> None:
    client = TestClient(app)
    response = client.get("/models")
    assert response.status_code == 200
    assert response.json()["data"][0]["version"] == "v1"
