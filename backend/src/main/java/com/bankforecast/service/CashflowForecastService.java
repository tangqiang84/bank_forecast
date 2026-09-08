package com.bankforecast.service;

import com.bankforecast.api.dto.ForecastRequest;
import com.bankforecast.api.dto.ForecastResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CashflowForecastService {

  public ForecastResponse forecast(ForecastRequest request) {
    List<BigDecimal> history = request.getHistory();
    int size = history.size();
    int window = Math.min(request.getWindowSize(), size);

    BigDecimal baseline = average(history.subList(size - window, size));
    BigDecimal trendStep = size > 1
        ? history.get(size - 1).subtract(history.get(0))
            .divide(BigDecimal.valueOf(size - 1L), 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    List<BigDecimal> forecastValues = new ArrayList<>();
    BigDecimal current = history.get(size - 1);
    for (int i = 0; i < request.getHorizon(); i++) {
      current = current.add(trendStep);
      forecastValues.add(current.setScale(2, RoundingMode.HALF_UP));
    }

    return new ForecastResponse("moving-average-with-trend", baseline, trendStep, forecastValues);
  }

  private BigDecimal average(List<BigDecimal> values) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal value : values) {
      sum = sum.add(value);
    }
    return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
  }
}
