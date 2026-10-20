package com.bankforecast.service;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class ContractService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  public ContractService(JdbcTemplate jdbcTemplate, AuditService auditService) { this.jdbcTemplate = jdbcTemplate; this.auditService = auditService; }

  public Map<String, Object> list(Long tenantId, int page, int pageSize, String contractNo, String projectNo, String status) {
    int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(pageSize, 1), 100), offset = (safePage - 1) * safeSize;
    String filter = " where c.tenant_id = ? and c.deleted_at is null and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) and (? is null or c.status = ?)";
    Object[] args = {tenantId, contractNo, contractNo, projectNo, projectNo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount, coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null" + filter + " group by c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount order by c.id desc limit ? offset ?", append(args, safeSize, offset));
    return page(items, safePage, safeSize, jdbcTemplate.queryForObject("select count(*) from contract c" + filter, args, Integer.class));
  }

  public Map<String, Object> receivables(Long tenantId, int page, int pageSize, String contractNo, String projectNo, LocalDate dateFrom, LocalDate dateTo, String status) {
    int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(pageSize, 1), 100), offset = (safePage - 1) * safeSize;
    String filter = " where p.tenant_id = ? and p.deleted_at is null and c.deleted_at is null and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) and (? is null or p.due_date >= ?) and (? is null or p.due_date <= ?) and (? is null or p.status = ?)";
    Object[] args = {tenantId, contractNo, contractNo, projectNo, projectNo, dateFrom, dateFrom, dateTo, dateTo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select p.id, p.contract_id, p.node_name, p.node_type, p.due_date, p.plan_amount, p.paid_amount, p.status, c.contract_no, c.contract_name, c.customer_name, u.display_name as owner_name from contract_receivable_plan p join contract c on c.id = p.contract_id left join user_account u on u.id = p.owner_user_id and u.tenant_id = p.tenant_id" + filter + " order by p.due_date, p.id limit ? offset ?", append(args, safeSize, offset));
    return page(items, safePage, safeSize, jdbcTemplate.queryForObject("select count(*) from contract_receivable_plan p join contract c on c.id = p.contract_id" + filter, args, Integer.class));
  }

  public Map<String, Object> detail(Long tenantId, Long id) {
    List<Map<String, Object>> contracts = jdbcTemplate.queryForList("select c.*, coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null where c.id = ? and c.tenant_id = ? and c.deleted_at is null group by c.id", id, tenantId);
    if (contracts.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "合同不存在");
    Map<String, Object> contract = new LinkedHashMap<>(contracts.get(0));
    Object receiveAccountId = contract.get("receive_account_id");
    if (receiveAccountId != null) {
      List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
          "select account_name, account_no_last4 from bank_account where id = ? and tenant_id = ?",
          receiveAccountId, tenantId);
      if (!accounts.isEmpty()) contract.put("receive_account", accounts.get(0));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("contract", contract);
    data.put("receivables", jdbcTemplate.queryForList("select p.id, p.node_name, p.node_type, p.due_date, p.plan_amount, p.paid_amount, p.status, u.display_name as owner_name from contract_receivable_plan p left join user_account u on u.id = p.owner_user_id and u.tenant_id = p.tenant_id where p.contract_id = ? and p.tenant_id = ? and p.deleted_at is null order by p.due_date, p.id", id, tenantId));
    data.put("transactions", jdbcTemplate.queryForList("select distinct bt.id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.counterparty_name, bt.summary, bt.match_status from bank_transaction bt join match_result mr on mr.bank_transaction_id = bt.id where mr.contract_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by bt.transaction_date desc, bt.id desc", id, tenantId));
    data.put("audit_logs", jdbcTemplate.queryForList("select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log where tenant_id = ? and target_type = 'contract' and target_id = ? order by id desc", tenantId, String.valueOf(id)));
    return data;
  }

  public Map<String, Object> create(Long tenantId, Map<String, Object> request) {
    String contractNo = requiredText(request, "contract_no"), contractName = requiredText(request, "contract_name"), customerName = requiredText(request, "customer_name");
    BigDecimal contractAmount = requiredAmount(request, "contract_amount");
    String status = optionalText(request, "status");
    if (status == null) status = "active";
    if (!Arrays.asList("active", "closed", "disabled").contains(status)) throw new BusinessException(ErrorCode.PARAM_ERROR, "合同状态不合法");
    Long projectId = optionalLong(request.get("project_id"));
    String projectNo = null, projectName = null;
    if (projectId != null) {
      List<Map<String, Object>> projects = jdbcTemplate.queryForList("select id, project_no, project_name from project where id = ? and tenant_id = ? and deleted_at is null", projectId, tenantId);
      if (projects.isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "关联项目不存在");
      projectNo = String.valueOf(projects.get(0).get("project_no"));
      projectName = String.valueOf(projects.get(0).get("project_name"));
    }
    java.sql.Date signDate = optionalDate(request, "sign_date"), startDate = optionalDate(request, "start_date"), endDate = optionalDate(request, "end_date");
    Integer exists = jdbcTemplate.queryForObject("select count(*) from contract where tenant_id = ? and contract_no = ? and deleted_at is null", Integer.class, tenantId, contractNo);
    if (exists != null && exists > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "合同编号已存在");
    KeyHolder keyHolder = new GeneratedKeyHolder();
    String finalStatus = status, finalProjectNo = projectNo, finalProjectName = projectName;
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, sign_date, start_date, end_date, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setString(2, contractNo); ps.setString(3, contractName); ps.setString(4, customerName);
      if (projectId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, projectId);
      ps.setString(6, finalProjectNo); ps.setString(7, finalProjectName); ps.setBigDecimal(8, contractAmount);
      ps.setDate(9, signDate); ps.setDate(10, startDate); ps.setDate(11, endDate); ps.setString(12, finalStatus);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "合同创建失败");
    Long id = key.longValue();
    auditService.record("CREATE_CONTRACT", "contract", String.valueOf(id), request.toString());
    return jdbcTemplate.queryForMap("select id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, sign_date, start_date, end_date, status, created_at, updated_at from contract where id = ? and tenant_id = ?", id, tenantId);
  }

  public Map<String, Object> createReceivablePlan(Long tenantId, Map<String, Object> request) {
    Long contractId = optionalLong(request.get("contract_id"));
    if (contractId == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "contract_id 不能为空");
    List<Map<String, Object>> contracts = jdbcTemplate.queryForList("select id, contract_amount from contract where id = ? and tenant_id = ? and deleted_at is null", contractId, tenantId);
    if (contracts.isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "关联合同不存在");
    BigDecimal contractAmount = new BigDecimal(String.valueOf(contracts.get(0).get("contract_amount")));
    String nodeName = requiredText(request, "node_name"), nodeType = requiredText(request, "node_type");
    if (!Arrays.asList("advance", "milestone", "acceptance", "warranty", "maintenance").contains(nodeType)) throw new BusinessException(ErrorCode.PARAM_ERROR, "节点类型不合法");
    java.sql.Date dueDate = requiredDate(request, "due_date");
    BigDecimal planAmount = requiredAmount(request, "plan_amount");
    Integer exists = jdbcTemplate.queryForObject("select count(*) from contract_receivable_plan where tenant_id = ? and contract_id = ? and node_name = ? and deleted_at is null", Integer.class, tenantId, contractId, nodeName);
    if (exists != null && exists > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "该合同下节点名称已存在");
    if (planAmount.compareTo(contractAmount) > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "应收计划金额不能超过合同金额");
    BigDecimal planned = jdbcTemplate.queryForObject("select coalesce(sum(plan_amount), 0) from contract_receivable_plan where tenant_id = ? and contract_id = ? and deleted_at is null", BigDecimal.class, tenantId, contractId);
    if (planned != null && planned.add(planAmount).compareTo(contractAmount) > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "应收计划金额合计不能超过合同金额");
    Long ownerUserId = optionalLong(request.get("owner_user_id"));
    if (ownerUserId != null) {
      Integer ownerExists = jdbcTemplate.queryForObject("select count(*) from user_account where id = ? and tenant_id = ? and status = 'active' and deleted_at is null", Integer.class, ownerUserId, tenantId);
      if (ownerExists == null || ownerExists == 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "责任人不存在");
    }
    Long projectId = jdbcTemplate.queryForObject("select project_id from contract where id = ? and tenant_id = ?", Long.class, contractId, tenantId);
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, status, owner_user_id) values (?, ?, ?, ?, ?, ?, ?, 'unpaid', ?)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setLong(2, contractId);
      if (projectId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, projectId);
      ps.setString(4, nodeName); ps.setString(5, nodeType); ps.setDate(6, dueDate); ps.setBigDecimal(7, planAmount);
      if (ownerUserId == null) ps.setNull(8, java.sql.Types.BIGINT); else ps.setLong(8, ownerUserId);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "应收计划创建失败");
    Long id = key.longValue();
    auditService.record("CREATE_RECEIVABLE_PLAN", "contract_receivable_plan", String.valueOf(id), request.toString());
    return jdbcTemplate.queryForMap("select id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status, owner_user_id, created_at, updated_at from contract_receivable_plan where id = ? and tenant_id = ?", id, tenantId);
  }

  private String requiredText(Map<String, Object> request, String field) { String value = optionalText(request, field); if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); return value; }
  private String optionalText(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim(); }
  private Long optionalLong(Object value) { if (value == null || String.valueOf(value).trim().isEmpty()) return null; try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "数值格式错误"); } }
  private BigDecimal requiredAmount(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); if (value == null || String.valueOf(value).trim().isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); try { BigDecimal amount = new BigDecimal(String.valueOf(value).trim()); if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 必须大于 0"); return amount; } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 格式错误"); } }
  private java.sql.Date optionalDate(Map<String, Object> request, String field) { String value = optionalText(request, field); if (value == null) return null; try { return java.sql.Date.valueOf(LocalDate.parse(value)); } catch (Exception ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 日期格式错误"); } }
  private java.sql.Date requiredDate(Map<String, Object> request, String field) { java.sql.Date value = optionalDate(request, field); if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); return value; }

  private Map<String, Object> page(List<Map<String, Object>> items, int page, int pageSize, Integer total) {
    Map<String, Object> data = new LinkedHashMap<>(); data.put("items", items); data.put("page", page); data.put("page_size", pageSize); data.put("total", total == null ? 0 : total); return data;
  }
  private Object[] append(Object[] values, Object... extra) { Object[] result = new Object[values.length + extra.length]; System.arraycopy(values, 0, result, 0, values.length); System.arraycopy(extra, 0, result, values.length, extra.length); return result; }
}
