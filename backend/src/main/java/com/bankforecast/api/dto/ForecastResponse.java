package com.bankforecast.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class ForecastResponse {

  private String method;
  private BigDecimal baseline;
  private BigDecimal trendStep;
  private List<BigDecimal> forecastValues = new ArrayList<BigDecimal>();

  public ForecastResponse() {}

  public ForecastResponse(String method, BigDecimal baseline, BigDecimal trendStep,
      List<BigDecimal> forecastValues) {
    this.method = method;
    this.baseline = baseline;
    this.trendStep = trendStep;
    this.forecastValues = forecastValues;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public BigDecimal getBaseline() {
    return baseline;
  }

  public void setBaseline(BigDecimal baseline) {
    this.baseline = baseline;
  }

  public BigDecimal getTrendStep() {
    return trendStep;
  }

  public void setTrendStep(BigDecimal trendStep) {
    this.trendStep = trendStep;
  }

  public List<BigDecimal> getForecastValues() {
    return forecastValues;
  }

  public void setForecastValues(List<BigDecimal> forecastValues) {
    this.forecastValues = forecastValues;
  }
}
