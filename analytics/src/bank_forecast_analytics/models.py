from __future__ import annotations

import math
import os
from datetime import date

from pydantic import BaseModel, Field, field_validator, model_validator


MODEL_REGISTRY = {
    "v1": {
        "version": "v1",
        "model_name": "moving-average-with-trend",
        "status": "active",
        "description": "移动平均 + 线性趋势（基线模型，向后兼容）",
    },
    "v2": {
        "version": "v2",
        "model_name": "weekly-seasonal-naive",
        "status": "active",
        "description": "星期几分组中位数 + 中位数稳健趋势，抗离群值",
    },
    "v3": {
        "version": "v3",
        "model_name": "holt-linear",
        "status": "active",
        "description": "Holt 双参数指数平滑，参数网格回测择优",
    },
}

OUTLIER_HANDLINGS = {"iqr_clip", "none"}


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
    dates: list[str] | None = None
    horizon: int = Field(default=7, ge=1, le=90)
    window_size: int = Field(default=3, ge=1, le=30)
    model_version: str = Field(default="v1", min_length=1, max_length=64)
    outlier_handling: str = Field(default="iqr_clip", min_length=1, max_length=32)

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

    @field_validator("dates")
    @classmethod
    def validate_dates(cls, dates: list[str] | None) -> list[str] | None:
        if dates is None:
            return None
        parsed: list[date] = []
        for value in dates:
            try:
                parsed.append(date.fromisoformat(value))
            except ValueError:
                raise ValueError("dates 必须是 yyyy-MM-dd 格式") from None
        for previous, current in zip(parsed, parsed[1:]):
            if current <= previous:
                raise ValueError("dates 必须严格递增")
        return dates

    @model_validator(mode="after")
    def validate_window_and_model(self) -> "ForecastRequest":
        if self.window_size > len(self.history):
            raise ValueError("window_size 不能大于 history 长度")
        if self.dates is not None and len(self.dates) != len(self.history):
            raise ValueError("dates 长度必须与 history 一致")
        if self.outlier_handling not in OUTLIER_HANDLINGS:
            raise ValueError("outlier_handling 仅支持 iqr_clip/none")
        model = MODEL_REGISTRY.get(self.model_version)
        if model is None:
            raise ValueError("模型版本不存在")
        if model.get("status") != "active":
            raise ValueError("模型版本未启用")
        return self


class BacktestMetrics(BaseModel):
    mae: float
    rmse: float
    points: int


class ForecastResponse(BaseModel):
    method: str
    model_version: str
    baseline: float
    trend_step: float
    forecast_values: list[float]
    confidence_lower: list[float]
    confidence_upper: list[float]
    backtest: BacktestMetrics


class ModelEvaluation(BaseModel):
    model_version: str
    model_name: str
    mae: float
    rmse: float
    points: int


class EvaluateResponse(BaseModel):
    evaluations: list[ModelEvaluation]
    recommended_model_version: str
