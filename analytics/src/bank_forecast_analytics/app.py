from __future__ import annotations

import os
from datetime import datetime, timezone

import uvicorn
from fastapi import FastAPI

from .common import ok
from .forecast import forecast_cashflow
from .models import ForecastRequest


def create_app() -> FastAPI:
    app = FastAPI(title="bank_forecast analytics", version="0.1.0")

    @app.get("/health")
    def health() -> object:
        return ok({
            "service": "bank-forecast-analytics",
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

    return app


app = create_app()


def main() -> None:
    host = os.getenv("ANALYTICS_HOST", "0.0.0.0")
    port = int(os.getenv("ANALYTICS_PORT", "8001"))
    uvicorn.run("bank_forecast_analytics.app:app", host=host, port=port, reload=True)


if __name__ == "__main__":
    main()
