package com.bankforecast.service;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class ForecastJobService {

  private final JdbcTemplate jdbcTemplate;
  private final RestTemplate analyticsRestTemplate;
  private final AuditService auditService;
  private final String analyticsBaseUrl;

  public ForecastJobService(
      JdbcTemplate jdbcTemplate,
      RestTemplate analyticsRestTemplate,
      AuditService auditService,
      @Value("${bank-forecast.analytics.base-url:http://localhost:8001}") String analyticsBaseUrl) {
    this.jdbcTemplate = jdbcTemplate;
    this.analyticsRestTemplate = analyticsRestTemplate;
    this.auditService = auditService;
    this.analyticsBaseUrl = analyticsBaseUrl;
  }

  public Map<String, Object> run(int horizon, int windowSize) {
    AuthPrincipal principal = requireAuth();
    validateOptions(horizon, windowSize);
    Long jobId = createJob(principal.getTenantId(), horizon, windowSize);
    return execute(principal, jobId, horizon, windowSize);
  }

  public Map<String, Object> retry(Long jobId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> job = findJob(principal.getTenantId(), jobId);
    if (!"failed".equals(text(job.get("status")))) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "只有失败的预测任务可以重试");
    }
    int attempts = number(job.get("attempt_count")).intValue();
    if (attempts >= 3) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "预测任务最多重试 3 次");
    }
    int horizon = number(job.get("horizon")).intValue();
    int windowSize = number(job.get("window_size")).intValue();
    return execute(principal, jobId, horizon, windowSize);
  }

  public Map<String, Object> latest() {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id from forecast_job where tenant_id = ? and status = 'success' and deleted_at is null order by id desc limit 1",
        principal.getTenantId());
    if (rows.isEmpty()) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("job", null);
      data.put("results", Collections.emptyList());
      return data;
    }
    return detail(number(rows.get(0).get("id")).longValue());
  }

  public Map<String, Object> detail(Long jobId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> job = findJob(principal.getTenantId(), jobId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job", job);
    data.put("results", jdbcTemplate.queryForList(
        "select id, forecast_date, forecast_amount, expected_receivable, projected_balance, actual_amount, deviation_amount, risk_level, risk_message "
            + "from forecast_result where tenant_id = ? and forecast_job_id = ? order by forecast_date, id",
        principal.getTenantId(), jobId));
    return data;
  }

  private Map<String, Object> execute(AuthPrincipal principal, Long jobId, int horizon, int windowSize) {
    Long tenantId = principal.getTenantId();
    Timestamp startedAt = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update(
        "update forecast_job set status = 'running', attempt_count = attempt_count + 1, error_message = null, started_at = ?, finished_at = null, updated_at = ? where id = ? and tenant_id = ?",
        startedAt, startedAt, jobId, tenantId);
    try {
      List<Map<String, Object>> historyRows = history(tenantId);
      if (historyRows.isEmpty()) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "暂无银行流水，无法生成现金流预测");
      }
      List<Double> history = new ArrayList<>();
      for (Map<String, Object> row : historyRows) {
        history.add(decimal(row.get("net_amount")).doubleValue());
      }
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("history", history);
      request.put("horizon", horizon);
      request.put("window_size", windowSize);
      ResponseEntity<Map> response = analyticsRestTemplate.postForEntity(
          analyticsBaseUrl + "/forecast/cashflow", request, Map.class);
      Map<String, Object> body = response.getBody();
      Map<String, Object> analyticsData = body == null ? null : map(body.get("data"));
      if (body == null || number(body.get("code")).intValue() != 0 || analyticsData == null) {
        throw new IllegalStateException("analytics 返回无效响应");
      }
      List<?> values = list(analyticsData.get("forecast_values"));
      if (values.size() != horizon) {
        throw new IllegalStateException("analytics 返回预测点数量不匹配");
      }
      writeResults(tenantId, jobId, values, horizon, historyRows);
      Timestamp finishedAt = Timestamp.valueOf(LocalDateTime.now());
      jdbcTemplate.update(
          "update forecast_job set status = 'success', model_name = ?, input_start_date = ?, input_end_date = ?, finished_at = ?, updated_at = ? where id = ? and tenant_id = ?",
          text(analyticsData.get("method")), historyDate(historyRows.get(0)), historyDate(historyRows.get(historyRows.size() - 1)),
          finishedAt, finishedAt, jobId, tenantId);
      auditService.record("RUN_FORECAST", "forecast_job", String.valueOf(jobId), "horizon=" + horizon);
      return detail(jobId);
    } catch (BusinessException ex) {
      markFailed(tenantId, jobId, ex.getMessage());
      throw ex;
    } catch (RestClientException | IllegalStateException ex) {
      markFailed(tenantId, jobId, ex.getMessage());
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "分析服务暂不可用，预测任务已记录为失败，可稍后重试");
    }
  }

  private void writeResults(Long tenantId, Long jobId, List<?> values, int horizon, List<Map<String, Object>> historyRows) {
    jdbcTemplate.update("delete from forecast_result where tenant_id = ? and forecast_job_id = ?", tenantId, jobId);
    BigDecimal projectedBalance = currentBalance(tenantId);
    LocalDate start = LocalDate.now();
    for (int i = 0; i < horizon; i++) {
      LocalDate forecastDate = start.plusDays(i);
      BigDecimal forecastAmount = decimal(values.get(i));
      BigDecimal expectedReceivable = expectedReceivable(tenantId, forecastDate);
      projectedBalance = projectedBalance.add(forecastAmount).add(expectedReceivable);
      String riskLevel = projectedBalance.compareTo(BigDecimal.ZERO) < 0 ? "high" : forecastAmount.compareTo(BigDecimal.ZERO) < 0 ? "medium" : "low";
      String riskMessage = "high".equals(riskLevel) ? "预计余额为负，请及时安排资金" : "medium".equals(riskLevel) ? "预计当日现金流为负" : "预计资金余额稳定";
      jdbcTemplate.update(
          "insert into forecast_result (tenant_id, forecast_job_id, forecast_date, forecast_amount, expected_receivable, projected_balance, risk_level, risk_message) values (?, ?, ?, ?, ?, ?, ?, ?)",
          tenantId, jobId, Date.valueOf(forecastDate), forecastAmount, expectedReceivable, projectedBalance, riskLevel, riskMessage);
    }
  }

  private List<Map<String, Object>> history(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select transaction_date, coalesce(sum(case when direction in ('income', 'refund') then amount when direction in ('expense', 'reversal') then -amount else 0 end), 0) as net_amount "
            + "from bank_transaction where tenant_id = ? and deleted_at is null group by transaction_date order by transaction_date",
        tenantId);
  }

  private BigDecimal expectedReceivable(Long tenantId, LocalDate date) {
    BigDecimal value = jdbcTemplate.queryForObject(
        "select coalesce(sum(plan_amount - paid_amount), 0) from contract_receivable_plan where tenant_id = ? and due_date = ? and status <> 'paid' and deleted_at is null",
        BigDecimal.class, tenantId, Date.valueOf(date));
    return value == null ? BigDecimal.ZERO.setScale(2) : value;
  }

  private BigDecimal currentBalance(Long tenantId) {
    BigDecimal value = jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null", BigDecimal.class, tenantId);
    return value == null ? BigDecimal.ZERO.setScale(2) : value;
  }

  private Long createJob(Long tenantId, int horizon, int windowSize) {
    org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      java.sql.PreparedStatement statement = connection.prepareStatement(
          "insert into forecast_job (tenant_id, status, horizon, window_size) values (?, 'pending', ?, ?)",
          java.sql.Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, tenantId);
      statement.setInt(2, horizon);
      statement.setInt(3, windowSize);
      return statement;
    }, keyHolder);
    Map<String, Object> generatedKeys = keyHolder.getKeys();
    Object generatedId = generatedKeys == null ? null : generatedKeys.get("id");
    if (generatedId == null && generatedKeys != null) {
      generatedId = generatedKeys.get("ID");
    }
    if (!(generatedId instanceof Number)) {
      throw new IllegalStateException("预测任务创建后未返回任务编号");
    }
    return ((Number) generatedId).longValue();
  }

  private void markFailed(Long tenantId, Long jobId, String message) {
    jdbcTemplate.update("update forecast_job set status = 'failed', error_message = ?, finished_at = ?, updated_at = ? where id = ? and tenant_id = ?",
        truncate(message == null ? "分析服务调用失败" : message, 1024), Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), jobId, tenantId);
  }

  private Map<String, Object> findJob(Long tenantId, Long jobId) {
    try {
      return jdbcTemplate.queryForMap("select id, status, model_name, horizon, window_size, input_start_date, input_end_date, attempt_count, error_message, started_at, finished_at, created_at from forecast_job where id = ? and tenant_id = ? and deleted_at is null", jobId, tenantId);
    } catch (EmptyResultDataAccessException ex) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "预测任务不存在");
    }
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }

  private void validateOptions(int horizon, int windowSize) {
    if (horizon < 1 || horizon > 90 || windowSize < 1 || windowSize > 30) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "预测天数需为 1-90，历史窗口需为 1-30");
    }
  }

  private LocalDate historyDate(Map<String, Object> row) {
    Object value = row.get("transaction_date");
    return value instanceof Date ? ((Date) value).toLocalDate() : LocalDate.parse(text(value));
  }

  private Map<String, Object> map(Object value) { return value instanceof Map ? (Map<String, Object>) value : null; }
  private List<?> list(Object value) { return value instanceof List ? (List<?>) value : Collections.emptyList(); }
  private Number number(Object value) { return value instanceof Number ? (Number) value : Integer.valueOf(0); }
  private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP); }
  private String text(Object value) { return value == null ? "" : value.toString().trim(); }
  private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
