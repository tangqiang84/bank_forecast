package com.bankforecast.payable;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PayableService {
  private final JdbcTemplate jdbcTemplate;

  public PayableService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> list(int page, int pageSize, String status) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    String statusFilter = status == null || status.trim().isEmpty() ? null : status.trim();
    if (statusFilter != null && !CsvPayablePlanParser.ALLOWED_STATUSES.contains(statusFilter)) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "status 仅支持 unpaid/partial/paid");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", jdbcTemplate.queryForList(
        "select p.id, p.contract_id, c.contract_no, p.supplier_name, p.node_name, p.due_date, p.plan_amount, p.paid_amount, p.status, p.remark, p.created_at "
            + "from contract_payable_plan p left join contract c on c.id = p.contract_id and c.tenant_id = p.tenant_id "
            + "where p.tenant_id = ? and p.deleted_at is null and (? is null or p.status = ?) order by p.due_date asc, p.id asc limit ? offset ?",
        tenantId, statusFilter, statusFilter, safeSize, (safePage - 1) * safeSize));
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", jdbcTemplate.queryForObject(
        "select count(*) from contract_payable_plan p where p.tenant_id = ? and p.deleted_at is null and (? is null or p.status = ?)",
        Integer.class, tenantId, statusFilter, statusFilter));
    return data;
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
