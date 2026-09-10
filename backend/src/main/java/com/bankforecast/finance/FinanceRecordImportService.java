package com.bankforecast.finance;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.CsvRowError;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FinanceRecordImportService {
  private final JdbcTemplate jdbcTemplate;
  private final CsvFinanceRecordParser parser;
  private final AuditService auditService;
  private final int maxRows;
  private final long maxFileSize;
  private final BigDecimal maxAmount;

  public FinanceRecordImportService(JdbcTemplate jdbcTemplate, CsvFinanceRecordParser parser, AuditService auditService,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize,
      @Value("${bank-forecast.import.max-amount}") BigDecimal maxAmount) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.auditService = auditService;
    this.maxRows = maxRows;
    this.maxFileSize = maxFileSize;
    this.maxAmount = maxAmount;
  }

  @Transactional
  public Map<String, Object> importRecords(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "finance-records.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId, fileName);
    int success = 0;
    int failed = 0;
    int skipped = 0;
    String error = null;
    CsvParseResult<CsvFinanceRecordRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertImportError(tenantId, jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvFinanceRecordRow row : parsed.getRows()) {
      try {
        validateAmount(row);
        if (exists(tenantId, row)) { skipped++; continue; }
        insertRecord(tenantId, row);
        success++;
      } catch (DuplicateKeyException ex) {
        skipped++;
      } catch (BusinessException ex) {
        failed++;
        CsvRowError rowError = new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson());
        insertImportError(tenantId, jobId, rowError);
        error = appendError(error, ex.getMessage());
      }
    }
    String status = failed == 0 ? "success" : (success == 0 ? "failed" : "partial_success");
    finishJob(jobId, parsed.getTotalRows(), success, failed, skipped, status, error);
    auditService.record("IMPORT_FINANCE_RECORD", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, tenantId);
    result.put("error_details", listErrors(jobId, tenantId));
    return result;
  }

  private void validateAmount(CsvFinanceRecordRow row) {
    if (row.getAmount().scale() > 2 || row.getAmount().compareTo(maxAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行 amount 超出金额边界");
    }
  }

  private boolean exists(Long tenantId, CsvFinanceRecordRow row) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from finance_record where tenant_id = ? and record_no = ? and coalesce(source_system, '') = coalesce(?, '') and deleted_at is null",
        Integer.class, tenantId, row.getRecordNo(), row.getSourceSystem());
    return count != null && count > 0;
  }

  private void insertRecord(Long tenantId, CsvFinanceRecordRow row) {
    jdbcTemplate.update(
        "insert into finance_record (tenant_id, record_no, record_type, record_date, posting_date, counterparty_name, amount, summary, source_system, status, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)",
        tenantId, row.getRecordNo(), row.getRecordType(), Date.valueOf(row.getRecordDate()),
        row.getPostingDate() == null ? null : Date.valueOf(row.getPostingDate()), row.getCounterpartyName(),
        row.getAmount(), appendRemark(row.getSummary(), row.getRemark()), row.getSourceSystem(),
        currentUserId(), currentUserId());
  }

  private String appendRemark(String summary, String remark) {
    if (remark == null || remark.trim().isEmpty()) return summary;
    return summary == null || summary.trim().isEmpty() ? "备注：" + remark : summary + "；备注：" + remark;
  }

  private Long createJob(Long tenantId, String fileName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into import_job (tenant_id, job_type, source_type, file_name, status, started_at) values (?, 'finance_record', 'file', ?, 'running', ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setString(2, fileName); ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now())); return ps;
    }, keyHolder);
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
      return ((Number) keyHolder.getKeys().get("id")).longValue();
    }
    return keyHolder.getKey().longValue();
  }

  private void finishJob(Long jobId, int total, int success, int failed, int skipped, String status, String error) {
    jdbcTemplate.update("update import_job set status = ?, total_rows = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, error_message = ?, finished_at = ?, updated_at = ? where id = ?",
        status, total, success, failed, skipped, error, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), jobId);
  }

  private Map<String, Object> getJob(Long jobId, Long tenantId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message, started_at, finished_at from import_job where id = ? and tenant_id = ? and deleted_at is null",
        jobId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入任务不存在");
    return new LinkedHashMap<>(rows.get(0));
  }

  private List<Map<String, Object>> listErrors(Long jobId, Long tenantId) {
    return jdbcTemplate.queryForList("select row_no, field_name, raw_json, error_message from import_row_error where import_job_id = ? and tenant_id = ? order by row_no", jobId, tenantId);
  }

  private void insertImportError(Long tenantId, Long jobId, CsvRowError error) {
    jdbcTemplate.update("insert into import_row_error (tenant_id, import_job_id, row_no, field_name, raw_json, error_message) values (?, ?, ?, ?, ?, ?)",
        tenantId, jobId, error.getRowNo(), error.getField(), error.getRawJson(), error.getMessage());
  }

  private String appendError(String current, String next) { return current == null ? next : (current.length() > 900 ? current : current + "；" + next); }

  private java.io.InputStream open(MultipartFile file) {
    try { return file.getInputStream(); } catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件读取失败"); }
  }

  private Long currentUserId() { return AuthContext.get() == null ? null : AuthContext.get().getUserId(); }
  private AuthPrincipal requireAuth() { if (AuthContext.get() == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return AuthContext.get(); }
}
