from __future__ import annotations

import math
import os

from pydantic import BaseModel, Field, field_validator, model_validator


MODEL_REGISTRY = {
    "v1": {
        "version": "v1",
        "model_name": "moving-average-with-trend",
        "status": "active",
    },
}


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


class ForecastRequest(BaseModel):
    history: list[float] = Field(min_length=1)
    horizon: int = Field(default=7, ge=1, le=90)
    window_size: int = Field(default=3, ge=1, le=30)
    model_version: str = Field(default="v1", min_length=1, max_length=64)

    @field_validator("history")
    @classmethod
    def validate_history(cls, history: list[float]) -> list[float]:
        max_length = _int_env("ANALYTICS_HISTORY_MAX_LENGTH", 365)
        max_abs_value = _float_env("ANALYTICS_HISTORY_MAX_ABS_VALUE", 1_000_000_000_000.0)
        if len(history) > max_length:
            raise ValueError(f"history 长度不能超过 {max_length}")
        for value in history:
            if not math.isfinite(value):
                raise ValueError("history 不能包含 NaN 或无穷大")
            if abs(value) > max_abs_value:
                raise ValueError(f"history 单个数值绝对值不能超过 {max_abs_value}")
        return history

    @model_validator(mode="after")
    def validate_window_and_model(self) -> "ForecastRequest":
        if self.window_size > len(self.history):
            raise ValueError("window_size 不能大于 history 长度")
        model = MODEL_REGISTRY.get(self.model_version)
        if model is None:
            raise ValueError("模型版本不存在")
        if model.get("status") != "active":
            raise ValueError("模型版本未启用")
        return self


class ForecastResponse(BaseModel):
    method: str
    model_version: str
    baseline: float
    trend_step: float
    forecast_values: list[float]
