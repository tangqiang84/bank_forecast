package com.bankforecast.payable;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class PayableService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public PayableService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
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

  public Map<String, Object> create(Long tenantId, Map<String, Object> request) {
    String supplierName = requiredText(request, "supplier_name"), nodeName = requiredText(request, "node_name");
    Date dueDate = requiredDate(request, "due_date");
    BigDecimal planAmount = requiredAmount(request, "plan_amount");
    String remark = optionalText(request, "remark");
    Long contractId = optionalLong(request.get("contract_id"));
    if (contractId != null) {
      Integer contractExists = jdbcTemplate.queryForObject("select count(*) from contract where id = ? and tenant_id = ? and deleted_at is null", Integer.class, contractId, tenantId);
      if (contractExists == null || contractExists == 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "关联合同不存在");
    }
    Integer exists = jdbcTemplate.queryForObject(
        "select count(*) from contract_payable_plan where tenant_id = ? and coalesce(contract_id, 0) = ? and supplier_name = ? and node_name = ? and due_date = ? and plan_amount = ? and deleted_at is null",
        Integer.class, tenantId, contractId == null ? 0L : contractId, supplierName, nodeName, dueDate, planAmount);
    if (exists != null && exists > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "相同应付计划已存在");
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into contract_payable_plan (tenant_id, contract_id, supplier_name, node_name, due_date, plan_amount, paid_amount, status, remark) values (?, ?, ?, ?, ?, ?, 0, 'unpaid', ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      if (contractId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, contractId);
      ps.setString(3, supplierName); ps.setString(4, nodeName); ps.setDate(5, dueDate);
      ps.setBigDecimal(6, planAmount); ps.setString(7, remark);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "应付计划创建失败");
    Long id = key.longValue();
    auditService.record("CREATE_PAYABLE_PLAN", "contract_payable_plan", String.valueOf(id), request.toString());
    return jdbcTemplate.queryForMap("select id, contract_id, supplier_name, node_name, due_date, plan_amount, paid_amount, status, remark, created_at, updated_at from contract_payable_plan where id = ? and tenant_id = ?", id, tenantId);
  }

  private String requiredText(Map<String, Object> request, String field) { String value = optionalText(request, field); if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); return value; }
  private String optionalText(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim(); }
  private Long optionalLong(Object value) { if (value == null || String.valueOf(value).trim().isEmpty()) return null; try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "数值格式错误"); } }
  private BigDecimal requiredAmount(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); if (value == null || String.valueOf(value).trim().isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); try { BigDecimal amount = new BigDecimal(String.valueOf(value).trim()); if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 必须大于 0"); return amount; } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 格式错误"); } }
  private Date requiredDate(Map<String, Object> request, String field) { String value = optionalText(request, field); if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); try { return Date.valueOf(LocalDate.parse(value)); } catch (Exception ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 日期格式错误"); } }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
