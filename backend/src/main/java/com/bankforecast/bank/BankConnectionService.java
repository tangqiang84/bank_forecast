package com.bankforecast.bank;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.BankTemplateCatalog;
import com.bankforecast.importjob.CsvBankStatementParser;
import com.bankforecast.importjob.CsvBankStatementRow;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.ExcelBankStatementParser;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BankConnectionService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final BankTemplateCatalog templateCatalog;
  private final ExcelBankStatementParser excelParser;
  private final CsvBankStatementParser csvParser;
  private final int maxRows;
  private final long maxFileSize;

  public BankConnectionService(JdbcTemplate jdbcTemplate, AuditService auditService,
      BankTemplateCatalog templateCatalog, ExcelBankStatementParser excelParser,
      CsvBankStatementParser csvParser,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.templateCatalog = templateCatalog;
    this.excelParser = excelParser;
    this.csvParser = csvParser;
    this.maxRows = maxRows;
    this.maxFileSize = maxFileSize;
  }

  public List<Map<String, Object>> list(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select c.id, c.bank_code, c.bank_name, c.connection_type, c.bank_account_id, a.account_name, a.account_no_last4, c.status, c.last_tested_at, c.last_test_status, c.last_test_message, c.remark, c.created_at, c.updated_at "
            + "from bank_connection c left join bank_account a on a.id = c.bank_account_id and a.tenant_id = c.tenant_id "
            + "where c.tenant_id = ? and c.deleted_at is null order by c.id desc",
        tenantId);
  }

  public List<Map<String, Object>> templates() {
    return templateCatalog.templates();
  }

  @Transactional
  public Map<String, Object> create(Long tenantId, Long userId, Map<String, Object> request) {
    String bankCode = requiredText(request, "bank_code");
    String bankName = textOrNull(request.get("bank_name"));
    if (bankName == null) bankName = bankCode;
    Long bankAccountId = optionalLong(request.get("bank_account_id"));
    if (bankAccountId != null) ensureAccount(tenantId, bankAccountId);
    KeyHolder holder = new GeneratedKeyHolder();
    String finalBankName = bankName;
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into bank_connection (tenant_id, bank_code, bank_name, connection_type, bank_account_id, status, remark, created_by) values (?, ?, ?, 'file', ?, 'active', ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setString(2, bankCode);
      ps.setString(3, finalBankName);
      if (bankAccountId == null) ps.setObject(4, null); else ps.setLong(4, bankAccountId);
      ps.setString(5, textOrNull(request.get("remark")));
      ps.setLong(6, userId);
      return ps;
    }, holder);
    Long id = generatedId(holder);
    auditService.record("CREATE_BANK_CONNECTION", "bank_connection", String.valueOf(id), "bank_code=" + bankCode);
    return find(tenantId, id);
  }

  @Transactional
  public Map<String, Object> update(Long tenantId, Long id, Map<String, Object> request) {
    find(tenantId, id);
    String bankName = textOrNull(request.get("bank_name"));
    String status = textOrNull(request.get("status"));
    if (status != null && !status.equals("active") && !status.equals("disabled")) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "status 仅支持 active/disabled");
    }
    Long bankAccountId = request.containsKey("bank_account_id") ? optionalLong(request.get("bank_account_id")) : null;
    if (bankAccountId != null) ensureAccount(tenantId, bankAccountId);
    jdbcTemplate.update("update bank_connection set bank_name = coalesce(?, bank_name), bank_account_id = coalesce(?, bank_account_id), status = coalesce(?, status), remark = coalesce(?, remark), updated_at = current_timestamp where id = ? and tenant_id = ?",
        bankName, bankAccountId, status, textOrNull(request.get("remark")), id, tenantId);
    auditService.record("UPDATE_BANK_CONNECTION", "bank_connection", String.valueOf(id), "");
    return find(tenantId, id);
  }

  @Transactional
  public Map<String, Object> delete(Long tenantId, Long id) {
    find(tenantId, id);
    jdbcTemplate.update("update bank_connection set deleted_at = current_timestamp where id = ? and tenant_id = ?", id, tenantId);
    auditService.record("DELETE_BANK_CONNECTION", "bank_connection", String.valueOf(id), "");
    return java.util.Collections.singletonMap("deleted", true);
  }

  @Transactional
  public Map<String, Object> test(Long tenantId, Long id, MultipartFile file) {
    Map<String, Object> connection = find(tenantId, id);
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.FILE_EMPTY, "连接测试需要上传该银行的样本流水文件");
    }
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
    long start = System.currentTimeMillis();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("connection_id", id);
    result.put("bank_code", connection.get("bank_code"));
    try {
      if (fileName.endsWith(".xlsx")) {
        ExcelBankStatementParser.ExcelParseResult parsed = excelParser.parse(open(file), maxRows);
        result.put("recognized_templates", parsed.getTemplates());
        result.put("parsed_rows", parsed.getRows().size());
        result.put("error_rows", parsed.getErrors().size());
        result.put("status", "success");
        result.put("message", "模板识别成功，解析出 " + parsed.getRows().size() + " 条流水");
      } else if (fileName.endsWith(".csv")) {
        CsvParseResult<CsvBankStatementRow> parsed = csvParser.parse(open(file), maxRows);
        result.put("parsed_rows", parsed.getRows().size());
        result.put("error_rows", parsed.getErrors().size());
        boolean success = !parsed.getRows().isEmpty();
        result.put("status", success ? "success" : "failed");
        result.put("message", success ? "CSV 解析成功，识别出 " + parsed.getRows().size() + " 条流水" : "CSV 未解析出有效流水行");
      } else {
        throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 CSV 或 XLSX 样本文件");
      }
    } catch (BusinessException ex) {
      result.put("status", "failed");
      result.put("message", ex.getMessage());
    }
    long duration = System.currentTimeMillis() - start;
    result.put("duration_ms", duration);
    jdbcTemplate.update("update bank_connection set last_tested_at = current_timestamp, last_test_status = ?, last_test_message = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
        result.get("status"), String.valueOf(result.get("message")), id, tenantId);
    auditService.record("TEST_BANK_CONNECTION", "bank_connection", String.valueOf(id),
        "status=" + result.get("status") + ", duration_ms=" + duration);
    return result;
  }

  private void ensureAccount(Long tenantId, Long bankAccountId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from bank_account where id = ? and tenant_id = ? and deleted_at is null",
        Integer.class, bankAccountId, tenantId);
    if (count == null || count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
  }

  private Map<String, Object> find(Long tenantId, Long id) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, bank_code, bank_name, connection_type, bank_account_id, status, last_tested_at, last_test_status, last_test_message, remark, created_at, updated_at from bank_connection where id = ? and tenant_id = ? and deleted_at is null",
        id, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行接入配置不存在");
    return rows.get(0);
  }

  private String requiredText(Map<String, Object> request, String field) {
    String value = textOrNull(request.get(field));
    if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空");
    return value;
  }

  private Long optionalLong(Object value) {
    if (value == null || String.valueOf(value).trim().isEmpty()) return null;
    try {
      return Long.valueOf(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "bank_account_id 格式不正确");
    }
  }

  private String textOrNull(Object value) {
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private java.io.InputStream open(MultipartFile file) {
    try { return file.getInputStream(); }
    catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件读取失败"); }
  }

  private Long generatedId(KeyHolder holder) {
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) {
      return ((Number) holder.getKeys().get("id")).longValue();
    }
    return holder.getKey().longValue();
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
}
