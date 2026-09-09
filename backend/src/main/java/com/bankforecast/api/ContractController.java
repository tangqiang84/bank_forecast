package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  public ApiResponse<Map<String, Object>> list() {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount, "
            + "coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount "
            + "from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null "
            + "where c.tenant_id = ? and c.deleted_at is null group by c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount order by c.id desc",
        principal.getTenantId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("total", items.size());
    return ApiResponse.ok(data);
  }

  @GetMapping("/receivables")
  public ApiResponse<List<Map<String, Object>>> receivables() {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(jdbcTemplate.queryForList(
        "select p.id, p.contract_id, p.node_name, p.node_type, p.due_date, p.plan_amount, p.paid_amount, p.status, c.contract_no, c.contract_name, c.customer_name "
            + "from contract_receivable_plan p join contract c on c.id = p.contract_id where p.tenant_id = ? and p.deleted_at is null order by p.due_date, p.id",
        principal.getTenantId()));
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
