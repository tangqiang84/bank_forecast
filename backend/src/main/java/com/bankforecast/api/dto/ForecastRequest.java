package com.bankforecast.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;

@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class ForecastRequest {

  @NotEmpty
  private List<BigDecimal> history = new ArrayList<BigDecimal>();

  @Min(1)
  private int horizon = 7;

  @Min(1)
  private int windowSize = 3;

  public ForecastRequest() {}

  public ForecastRequest(List<BigDecimal> history, int horizon, int windowSize) {
    this.history = history;
    this.horizon = horizon;
    this.windowSize = windowSize;
  }

  public List<BigDecimal> getHistory() {
    return history;
  }

  public void setHistory(List<BigDecimal> history) {
    this.history = history;
  }

  public int getHorizon() {
    return horizon;
  }

  public void setHorizon(int horizon) {
    this.horizon = horizon;
  }

  public int getWindowSize() {
    return windowSize;
  }

  public void setWindowSize(int windowSize) {
    this.windowSize = windowSize;
  }
}
