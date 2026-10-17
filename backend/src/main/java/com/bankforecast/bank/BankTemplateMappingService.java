package com.bankforecast.bank;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.BankTemplateCatalog;
import com.bankforecast.importjob.CsvImportSupport;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户级银行模板表头映射：解析流水文件时优先于内置别名。 */
@Service
public class BankTemplateMappingService {

  private static final List<String> STANDARD_FIELDS = Arrays.asList(
      "transaction_no", "transaction_date", "direction", "amount",
      "debit_amount", "credit_amount", "balance_after", "counterparty_name",
      "summary", "currency");
  private static final Map<String, String> FIELD_LABELS = fieldLabels();

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final BankTemplateCatalog templateCatalog;

  public BankTemplateMappingService(JdbcTemplate jdbcTemplate, AuditService auditService,
      BankTemplateCatalog templateCatalog) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.templateCatalog = templateCatalog;
  }

  /** 模板配置中心总览：内置八银行字典、可映射标准字段和租户自定义映射。 */
  public Map<String, Object> overview(Long tenantId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("templates", templateCatalog.templates());
    List<Map<String, Object>> fields = new ArrayList<>();
    for (String field : STANDARD_FIELDS) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("field", field);
      item.put("label", FIELD_LABELS.get(field));
      fields.add(item);
    }
    result.put("fields", fields);
    result.put("mappings", list(tenantId));
    return result;
  }

  public List<Map<String, Object>> list(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select id, header, field, created_at, updated_at from bank_template_mapping where tenant_id = ? order by id desc",
        tenantId);
  }

  /** 解析用别名表：归一化表头 → 标准字段。 */
  public Map<String, String> aliases(Long tenantId) {
    Map<String, String> aliases = new HashMap<>();
    for (Map<String, Object> row : list(tenantId)) {
      String key = CsvImportSupport.normalizeHeader(String.valueOf(row.get("header")));
      if (!key.isEmpty()) aliases.put(key, String.valueOf(row.get("field")));
    }
    return aliases;
  }

  @Transactional
  public Map<String, Object> create(Long tenantId, Long userId, Map<String, Object> request) {
    String header = requiredHeader(request);
    String field = requiredField(request);
    KeyHolder holder = new GeneratedKeyHolder();
    try {
      jdbcTemplate.update(connection -> {
        PreparedStatement ps = connection.prepareStatement(
            "insert into bank_template_mapping (tenant_id, header, field, created_by) values (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS);
        ps.setLong(1, tenantId);
        ps.setString(2, header);
        ps.setString(3, field);
        ps.setLong(4, userId);
        return ps;
      }, holder);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "该表头已存在映射，请直接编辑");
    }
    Long id = generatedId(holder);
    auditService.record("CREATE_TEMPLATE_MAPPING", "bank_template_mapping", String.valueOf(id),
        "header=" + header + ", field=" + field);
    return find(tenantId, id);
  }

  private Long generatedId(KeyHolder holder) {
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) {
      return ((Number) holder.getKeys().get("id")).longValue();
    }
    return holder.getKey().longValue();
  }

  @Transactional
  public Map<String, Object> update(Long tenantId, Long id, Map<String, Object> request) {
    find(tenantId, id);
    String header = requiredHeader(request);
    String field = requiredField(request);
    try {
      jdbcTemplate.update(
          "update bank_template_mapping set header = ?, field = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
          header, field, id, tenantId);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "该表头已存在映射，请直接编辑");
    }
    auditService.record("UPDATE_TEMPLATE_MAPPING", "bank_template_mapping", String.valueOf(id),
        "header=" + header + ", field=" + field);
    return find(tenantId, id);
  }

  @Transactional
  public Map<String, Object> delete(Long tenantId, Long id) {
    Map<String, Object> existing = find(tenantId, id);
    jdbcTemplate.update("delete from bank_template_mapping where id = ? and tenant_id = ?", id, tenantId);
    auditService.record("DELETE_TEMPLATE_MAPPING", "bank_template_mapping", String.valueOf(id),
        "header=" + existing.get("header"));
    return java.util.Collections.singletonMap("deleted", true);
  }

  private Map<String, Object> find(Long tenantId, Long id) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, header, field, created_at, updated_at from bank_template_mapping where id = ? and tenant_id = ?",
        id, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "模板映射不存在");
    return rows.get(0);
  }

  private String requiredHeader(Map<String, Object> request) {
    String header = textOrNull(request.get("header"));
    if (header == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "header 不能为空");
    if (header.length() > 128) throw new BusinessException(ErrorCode.PARAM_ERROR, "header 长度不能超过 128");
    if (CsvImportSupport.normalizeHeader(header).isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "header 归一化后为空，请提供有效表头");
    }
    return header;
  }

  private String requiredField(Map<String, Object> request) {
    String field = textOrNull(request.get("field"));
    if (field == null || !STANDARD_FIELDS.contains(field)) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "field 必须是标准字段之一：" + String.join("/", STANDARD_FIELDS));
    }
    return field;
  }

  private String textOrNull(Object value) {
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  public Long currentTenantId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getTenantId();
  }

  public Long currentUserId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getUserId();
  }

  private static Map<String, String> fieldLabels() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("transaction_no", "流水号");
    map.put("transaction_date", "交易日期");
    map.put("direction", "收支方向");
    map.put("amount", "交易金额");
    map.put("debit_amount", "借方金额（支出）");
    map.put("credit_amount", "贷方金额（收入）");
    map.put("balance_after", "余额");
    map.put("counterparty_name", "对方户名");
    map.put("summary", "摘要");
    map.put("currency", "币种");
    return map;
  }
}
