from __future__ import annotations

import math
from datetime import date, timedelta
from statistics import median, mean, pstdev, quantiles

from .models import (
    BacktestMetrics,
    EvaluateResponse,
    ForecastRequest,
    ForecastResponse,
    ModelEvaluation,
    MODEL_REGISTRY,
)

_CI_Z = 1.2816  # 约 80% 置信区间
_HOLT_ALPHAS = (0.2, 0.4, 0.6, 0.8)
_HOLT_BETAS = (0.05, 0.1, 0.2)


def forecast_cashflow(request: ForecastRequest) -> ForecastResponse:
    history = _prepare_history(request.history, request.outlier_handling)
    dates = _parse_dates(request.dates)
    values, baseline, trend_step = _forecast(request.model_version, history, dates, request.window_size, request.horizon)
    residuals = _residuals(request.model_version, history, dates, request.window_size)
    lower, upper = _confidence_intervals(values, residuals)
    return ForecastResponse(
        method=str(MODEL_REGISTRY[request.model_version]["model_name"]),
        model_version=request.model_version,
        baseline=baseline,
        trend_step=trend_step,
        forecast_values=values,
        confidence_lower=lower,
        confidence_upper=upper,
        backtest=_backtest(request.model_version, history, dates, request.window_size, request.horizon),
    )


def evaluate_models(request: ForecastRequest) -> EvaluateResponse:
    history = _prepare_history(request.history, request.outlier_handling)
    dates = _parse_dates(request.dates)
    evaluations: list[ModelEvaluation] = []
    for version, model in MODEL_REGISTRY.items():
        if model.get("status") != "active":
            continue
        metrics = _backtest(version, history, dates, request.window_size, request.horizon)
        evaluations.append(ModelEvaluation(
            model_version=version,
            model_name=str(model["model_name"]),
            mae=metrics.mae,
            rmse=metrics.rmse,
            points=metrics.points,
        ))
    scored = [item for item in evaluations if item.points > 0]
    recommended = min(scored, key=lambda item: item.mae) if scored else evaluations[0]
    return EvaluateResponse(evaluations=evaluations, recommended_model_version=recommended.model_version)


def _prepare_history(history: list[float], outlier_handling: str) -> list[float]:
    if outlier_handling != "iqr_clip" or len(history) < 4:
        return list(history)
    quartiles = quantiles(history, n=4)
    q1, q3 = quartiles[0], quartiles[2]
    iqr = q3 - q1
    if iqr == 0:
        return list(history)
    lower, upper = q1 - 1.5 * iqr, q3 + 1.5 * iqr
    return [min(max(value, lower), upper) for value in history]


def _parse_dates(raw_dates: list[str] | None) -> list[date] | None:
    if raw_dates is None:
        return None
    return [date.fromisoformat(value) for value in raw_dates]


def _forecast(version: str, history: list[float], dates: list[date] | None,
              window: int, horizon: int) -> tuple[list[float], float, float]:
    if version == "v2":
        return _forecast_weekly(history, dates, horizon)
    if version == "v3":
        return _forecast_holt(history, horizon)
    return _forecast_v1(history, window, horizon)


def _forecast_v1(history: list[float], window: int, horizon: int) -> tuple[list[float], float, float]:
    baseline = round(mean(history[-window:]), 2)
    trend_step = round((history[-1] - history[0]) / (len(history) - 1), 2) if len(history) > 1 else 0.0
    values = []
    current = history[-1]
    for _ in range(horizon):
        current = round(current + trend_step, 2)
        values.append(current)
    return values, baseline, trend_step


def _forecast_weekly(history: list[float], dates: list[date] | None,
                     horizon: int) -> tuple[list[float], float, float]:
    diffs = [b - a for a, b in zip(history, history[1:])]
    drift = round(median(diffs), 2) if diffs else 0.0
    baseline = round(median(history), 2)
    if dates is None:
        values = [round(baseline + drift * (i + 1), 2) for i in range(horizon)]
        return values, baseline, drift
    weekday_medians: dict[int, float] = {}
    for weekday in range(7):
        values_on_weekday = [value for value, day in zip(history, dates) if day.weekday() == weekday]
        if values_on_weekday:
            weekday_medians[weekday] = median(values_on_weekday)
    last_date = dates[-1]
    values = []
    for i in range(1, horizon + 1):
        forecast_date = last_date + timedelta(days=i)
        seasonal = weekday_medians.get(forecast_date.weekday(), baseline)
        values.append(round(seasonal + drift * i, 2))
    return values, baseline, drift


def _holt_fit(history: list[float], alpha: float, beta: float) -> tuple[list[float], float, float]:
    level = history[0]
    trend = history[1] - history[0] if len(history) > 1 else 0.0
    fitted = [level]
    for value in history[1:]:
        previous_level = level
        level = alpha * value + (1 - alpha) * (level + trend)
        trend = beta * (level - previous_level) + (1 - beta) * trend
        fitted.append(level + trend)
    return fitted, level, trend


def _holt_params(history: list[float]) -> tuple[float, float]:
    if len(history) < 3:
        return 0.4, 0.1
    best_alpha, best_beta, best_sse = 0.4, 0.1, math.inf
    for alpha in _HOLT_ALPHAS:
        for beta in _HOLT_BETAS:
            fitted, _, _ = _holt_fit(history, alpha, beta)
            sse = sum((actual - predicted) ** 2 for actual, predicted in zip(history[1:], fitted[1:]))
            if sse < best_sse:
                best_alpha, best_beta, best_sse = alpha, beta, sse
    return best_alpha, best_beta


def _forecast_holt(history: list[float], horizon: int) -> tuple[list[float], float, float]:
    alpha, beta = _holt_params(history)
    _, level, trend = _holt_fit(history, alpha, beta)
    values = [round(level + trend * (i + 1), 2) for i in range(horizon)]
    return values, round(level, 2), round(trend, 2)


def _one_step_fitted(version: str, history: list[float], dates: list[date] | None, window: int) -> list[float]:
    if version == "v3":
        alpha, beta = _holt_params(history)
        fitted, _, _ = _holt_fit(history, alpha, beta)
        return fitted
    fitted = [history[0]]
    for i in range(1, len(history)):
        if version == "v2":
            prefix = history[:i]
            diffs = [b - a for a, b in zip(prefix, prefix[1:])]
            drift = median(diffs) if diffs else 0.0
            if dates is not None:
                weekday = dates[i].weekday()
                same_weekday = [value for value, day in zip(prefix, dates) if day.weekday() == weekday]
                base = median(same_weekday) if same_weekday else median(prefix)
            else:
                base = median(prefix)
            fitted.append(base + drift)
        else:
            prefix = history[max(0, i - window):i]
            fitted.append(mean(prefix))
    return fitted


def _residuals(version: str, history: list[float], dates: list[date] | None, window: int) -> list[float]:
    if len(history) < 2:
        return [0.0]
    fitted = _one_step_fitted(version, history, dates, window)
    return [actual - predicted for actual, predicted in zip(history[1:], fitted[1:])]


def _confidence_intervals(values: list[float], residuals: list[float]) -> tuple[list[float], list[float]]:
    std = pstdev(residuals) if len(residuals) > 1 else 0.0
    lower = []
    upper = []
    for index, value in enumerate(values):
        half_width = _CI_Z * std * math.sqrt(index + 1)
        lower.append(round(value - half_width, 2))
        upper.append(round(value + half_width, 2))
    return lower, upper


def _backtest(version: str, history: list[float], dates: list[date] | None,
              window: int, horizon: int) -> BacktestMetrics:
    if len(history) < 6:
        return BacktestMetrics(mae=0.0, rmse=0.0, points=0)
    tail = min(horizon, max(3, len(history) // 4))
    train_history = history[:-tail]
    train_dates = dates[:-tail] if dates is not None else None
    effective_window = min(window, len(train_history))
    predicted, _, _ = _forecast(version, train_history, train_dates, effective_window, tail)
    actuals = history[-tail:]
    errors = [actual - predicted_value for actual, predicted_value in zip(actuals, predicted)]
    mae = round(mean(abs(error) for error in errors), 2)
    rmse = round(math.sqrt(mean(error ** 2 for error in errors)), 2)
    return BacktestMetrics(mae=mae, rmse=rmse, points=tail)
