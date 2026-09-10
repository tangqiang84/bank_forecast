package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private final JdbcTemplate jdbcTemplate;

  public ProjectController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "project_no", required = false) String projectNo,
      @RequestParam(name = "customer_name", required = false) String customerName,
      @RequestParam(name = "project_status", required = false) String projectStatus) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where p.tenant_id = ? and p.deleted_at is null "
        + "and (? is null or p.project_no = ?) and (? is null or p.customer_name like ?) "
        + "and (? is null or p.project_status = ?)";
    String customerLike = blankToNull(customerName) == null ? null : "%" + customerName.trim() + "%";
    Object[] args = {principal.getTenantId(), blankToNull(projectNo), blankToNull(projectNo), customerLike, customerLike,
        blankToNull(projectStatus), blankToNull(projectStatus)};
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select p.id, p.project_no, p.project_name, p.customer_name, p.project_manager, p.project_status, "
            + "coalesce((select sum(c.contract_amount) from contract c where c.project_id = p.id and c.deleted_at is null), 0) as contract_amount, "
            + "coalesce((select sum(r.plan_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as receivable_amount, "
            + "coalesce((select sum(r.paid_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as paid_amount, "
            + "coalesce((select sum(r.plan_amount - r.paid_amount) from contract_receivable_plan r where r.project_id = p.id and r.status <> 'paid' and r.due_date < current_date and r.deleted_at is null), 0) as overdue_amount, "
            + "(select count(*) from contract c where c.project_id = p.id and c.deleted_at is null) as contract_count, "
            + "(select count(*) from exception_case e where e.tenant_id = p.tenant_id and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = p.id) and e.status not in ('closed', 'false_positive') and e.deleted_at is null) as exception_count "
            + "from project p" + filter + " order by p.id desc limit ? offset ?", append(args, safeSize, offset));
    for (Map<String, Object> row : rows) enrichRisk(row);
    Integer total = jdbcTemplate.queryForObject("select count(*) from project p" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", rows);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return ApiResponse.ok(data);
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> projects = jdbcTemplate.queryForList(
        "select id, project_no, project_name, customer_name, project_manager, project_status, created_at, updated_at from project where id = ? and tenant_id = ? and deleted_at is null",
        id, principal.getTenantId());
    if (projects.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
    Map<String, Object> project = projects.get(0);
    Map<String, Object> metrics = jdbcTemplate.queryForMap(
        "select "
            + "coalesce((select sum(c.contract_amount) from contract c where c.project_id = ? and c.tenant_id = ? and c.deleted_at is null), 0) as contract_amount, "
            + "coalesce((select sum(r.plan_amount) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as receivable_amount, "
            + "coalesce((select sum(r.paid_amount) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as paid_amount, "
            + "coalesce((select sum(case when r.status <> 'paid' and r.due_date < current_date then r.plan_amount - r.paid_amount else 0 end) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as overdue_amount",
        id, principal.getTenantId(), id, principal.getTenantId(), id, principal.getTenantId(), id, principal.getTenantId());
    int exceptions = jdbcTemplate.queryForObject(
        "select count(*) from exception_case e where e.tenant_id = ? and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = ?) and e.status not in ('closed', 'false_positive') and e.deleted_at is null",
        Integer.class, principal.getTenantId(), id);
    Map<String, Object> summary = new LinkedHashMap<>(metrics);
    summary.put("exception_count", exceptions);
    enrichRisk(summary);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("project", project);
    data.put("summary", summary);
    data.put("contracts", jdbcTemplate.queryForList("select id, contract_no, contract_name, customer_name, contract_amount, status from contract where project_id = ? and tenant_id = ? and deleted_at is null order by id desc", id, principal.getTenantId()));
    data.put("receivables", jdbcTemplate.queryForList("select id, contract_id, node_name, node_type, due_date, plan_amount, paid_amount, status from contract_receivable_plan where project_id = ? and tenant_id = ? and deleted_at is null order by due_date, id", id, principal.getTenantId()));
    data.put("transactions", jdbcTemplate.queryForList("select distinct bt.id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.counterparty_name, bt.summary, bt.match_status from bank_transaction bt join match_result mr on mr.bank_transaction_id = bt.id where mr.project_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by bt.transaction_date desc, bt.id desc", id, principal.getTenantId()));
    data.put("exceptions", jdbcTemplate.queryForList("select e.id, e.exception_no, e.exception_type, e.title, e.status, e.severity, e.due_date from exception_case e where e.tenant_id = ? and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = ?) and e.deleted_at is null order by e.id desc", principal.getTenantId(), id));
    return ApiResponse.ok(data);
  }

  private void enrichRisk(Map<String, Object> row) {
    BigDecimal receivable = decimal(row.get("receivable_amount"));
    BigDecimal paid = decimal(row.get("paid_amount"));
    BigDecimal overdue = decimal(row.get("overdue_amount"));
    int exceptionCount = number(row.get("exception_count"));
    BigDecimal paidRate = receivable.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : paid.divide(receivable, 4, RoundingMode.HALF_UP);
    int riskScore = 100;
    if (overdue.compareTo(BigDecimal.ZERO) > 0) riskScore -= 30;
    if (paidRate.compareTo(new BigDecimal("0.5")) < 0) riskScore -= 30;
    else if (paidRate.compareTo(new BigDecimal("0.8")) < 0) riskScore -= 15;
    riskScore -= Math.min(30, exceptionCount * 10);
    String riskLevel = riskScore >= 80 ? "healthy" : riskScore >= 60 ? "warning" : "danger";
    row.put("paid_rate", paidRate);
    row.put("risk_score", riskScore);
    row.put("risk_level", riskLevel);
    List<String> risks = new ArrayList<>();
    if (overdue.compareTo(BigDecimal.ZERO) > 0) risks.add("存在逾期应收");
    if (paidRate.compareTo(new BigDecimal("0.8")) < 0) risks.add("回款进度低于80%");
    if (exceptionCount > 0) risks.add("存在待处理异常");
    row.put("risk_items", risks);
  }

  private int number(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
  private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
  private String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
  private Object[] append(Object[] values, Object... extra) { Object[] result = new Object[values.length + extra.length]; System.arraycopy(values, 0, result, 0, values.length); System.arraycopy(extra, 0, result, values.length, extra.length); return result; }
  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
