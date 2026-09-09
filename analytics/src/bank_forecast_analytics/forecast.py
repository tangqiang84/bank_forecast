from __future__ import annotations

from statistics import mean

from .models import ForecastRequest, ForecastResponse


def forecast_cashflow(request: ForecastRequest) -> ForecastResponse:
    history = request.history
    window = min(request.window_size, len(history))
    baseline = round(mean(history[-window:]), 2)

    if len(history) > 1:
        trend_step = round((history[-1] - history[0]) / (len(history) - 1), 2)
    else:
        trend_step = 0.0

    values: list[float] = []
    current = history[-1]
    for _ in range(request.horizon):
        current = round(current + trend_step, 2)
        values.append(current)

    return ForecastResponse(
        method="moving-average-with-trend",
        model_version=request.model_version,
        baseline=baseline,
        trend_step=trend_step,
        forecast_values=values,
    )
