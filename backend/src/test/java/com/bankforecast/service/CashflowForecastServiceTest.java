package com.bankforecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankforecast.api.dto.ForecastRequest;
import com.bankforecast.api.dto.ForecastResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CashflowForecastServiceTest {

  private final CashflowForecastService service = new CashflowForecastService();

  @Test
  void forecastProducesValues() {
    ForecastRequest request = new ForecastRequest(
        Arrays.asList(BigDecimal.valueOf(100), BigDecimal.valueOf(120), BigDecimal.valueOf(140)),
        2,
        2);

    ForecastResponse response = service.forecast(request);

    assertThat(response.getForecastValues()).hasSize(2);
    assertThat(response.getMethod()).isEqualTo("moving-average-with-trend");
  }
}
