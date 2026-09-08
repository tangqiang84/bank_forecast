from __future__ import annotations

from pydantic import BaseModel, Field


class ForecastRequest(BaseModel):
    history: list[float] = Field(min_length=1)
    horizon: int = Field(default=7, ge=1, le=90)
    window_size: int = Field(default=3, ge=1, le=30)


class ForecastResponse(BaseModel):
    method: str
    baseline: float
    trend_step: float
    forecast_values: list[float]

