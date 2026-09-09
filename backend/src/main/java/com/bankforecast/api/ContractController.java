package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {
  private final JdbcTemplate jdbcTemplate;

  public ContractController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "contract_no", required = false) String contractNo,
      @RequestParam(name = "project_no", required = false) String projectNo,
      @RequestParam(required = false) String status) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where c.tenant_id = ? and c.deleted_at is null "
        + "and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) and (? is null or c.status = ?)";
    Object[] args = {principal.getTenantId(), contractNo, contractNo, projectNo, projectNo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount, "
            + "coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount "
            + "from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null "
            + filter + " group by c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount order by c.id desc limit ? offset ?",
        append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*) from contract c" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return ApiResponse.ok(data);
  }

  @GetMapping("/receivables")
  public ApiResponse<Map<String, Object>> receivables(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "contract_no", required = false) String contractNo,
      @RequestParam(name = "project_no", required = false) String projectNo,
      @RequestParam(name = "date_from", required = false) LocalDate dateFrom,
      @RequestParam(name = "date_to", required = false) LocalDate dateTo,
      @RequestParam(required = false) String status) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where p.tenant_id = ? and p.deleted_at is null and c.deleted_at is null "
        + "and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) "
        + "and (? is null or p.due_date >= ?) and (? is null or p.due_date <= ?) and (? is null or p.status = ?)";
    Object[] args = {principal.getTenantId(), contractNo, contractNo, projectNo, projectNo,
        dateFrom, dateFrom, dateTo, dateTo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select p.id, p.contract_id, p.node_name, p.node_type, p.due_date, p.plan_amount, p.paid_amount, p.status, c.contract_no, c.contract_name, c.customer_name "
            + "from contract_receivable_plan p join contract c on c.id = p.contract_id" + filter
            + " order by p.due_date, p.id limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*) from contract_receivable_plan p join contract c on c.id = p.contract_id" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return ApiResponse.ok(data);
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> contracts = jdbcTemplate.queryForList(
        "select c.*, coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount "
            + "from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null "
            + "where c.id = ? and c.tenant_id = ? and c.deleted_at is null group by c.id", id, principal.getTenantId());
    if (contracts.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "合同不存在");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("contract", contracts.get(0));
    data.put("receivables", jdbcTemplate.queryForList(
        "select id, node_name, node_type, due_date, plan_amount, paid_amount, status from contract_receivable_plan where contract_id = ? and tenant_id = ? and deleted_at is null order by due_date, id", id, principal.getTenantId()));
    data.put("transactions", jdbcTemplate.queryForList(
        "select distinct bt.id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.counterparty_name, bt.summary, bt.match_status "
            + "from bank_transaction bt join match_result mr on mr.bank_transaction_id = bt.id where mr.contract_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by bt.transaction_date desc, bt.id desc", id, principal.getTenantId()));
    data.put("audit_logs", jdbcTemplate.queryForList(
        "select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log where tenant_id = ? and target_type = 'contract' and target_id = ? order by id desc", principal.getTenantId(), String.valueOf(id)));
    return ApiResponse.ok(data);
  }

  private Object[] append(Object[] values, Object... extra) {
    Object[] result = new Object[values.length + extra.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(extra, 0, result, values.length, extra.length);
    return result;
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
