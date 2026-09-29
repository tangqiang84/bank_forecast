package com.bankforecast.finance;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.CsvImportSupport;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.CsvRowError;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.PermissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
  private final PermissionRepository permissionRepository;
  private final ObjectMapper objectMapper;
  private final int maxRows;
  private final long maxFileSize;
  private final BigDecimal maxAmount;

  public FinanceRecordImportService(JdbcTemplate jdbcTemplate, CsvFinanceRecordParser parser, AuditService auditService,
      PermissionRepository permissionRepository, ObjectMapper objectMapper,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize,
      @Value("${bank-forecast.import.max-amount}") BigDecimal maxAmount) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.auditService = auditService;
    this.permissionRepository = permissionRepository;
    this.objectMapper = objectMapper;
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
        if ("skipped".equals(importRow(tenantId, row))) {
          skipped++;
        } else {
          success++;
        }
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

  private String importRow(Long tenantId, CsvFinanceRecordRow row) {
    validateAmount(row);
    if (exists(tenantId, row)) return "skipped";
    insertRecord(tenantId, row);
    return "success";
  }

  @Transactional
  public Map<String, Object> previewRecords(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "finance-records.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId, fileName);
    int valid = 0;
    int failed = 0;
    String error = null;
    CsvParseResult<CsvFinanceRecordRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertPreviewItem(tenantId, jobId, rowError.getRowNo(), "failed", rowError.getRawJson(), rowError.getMessage());
      insertImportError(tenantId, jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvFinanceRecordRow row : parsed.getRows()) {
      try {
        validateAmount(row);
        insertPreviewItem(tenantId, jobId, row.getRowNo(), "valid", writePayload(toPayload(row)), null);
        valid++;
      } catch (BusinessException ex) {
        failed++;
        insertPreviewItem(tenantId, jobId, row.getRowNo(), "failed", writePayload(toPayload(row)), ex.getMessage());
        insertImportError(tenantId, jobId, new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson()));
        error = appendError(error, ex.getMessage());
      }
    }
    String status = valid == 0 ? "preview_failed" : "preview_pending";
    finishJob(jobId, parsed.getTotalRows(), valid, failed, 0, status, error);
    auditService.record("PREVIEW_FINANCE_RECORD", "import_job", String.valueOf(jobId), "file=" + fileName);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> confirmPreview(Long jobId) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "reconciliation:run");
    Long tenantId = principal.getTenantId();
    Map<String, Object> job = getJob(jobId, tenantId);
    String status = String.valueOf(job.get("status"));
    if (!"preview_pending".equals(status) && !"preview_failed".equals(status)) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "当前导入任务不是待确认预览状态");
    }
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id, row_no, payload_json from import_preview_item where tenant_id = ? and job_id = ? and status in ('valid','retry_success') order by row_no",
        tenantId, jobId);
    int success = 0;
    int failed = 0;
    int skipped = 0;
    String error = null;
    for (Map<String, Object> item : items) {
      int rowNo = ((Number) item.get("row_no")).intValue();
      try {
        CsvFinanceRecordRow row = rowFromPayload(rowNo, readPayload(item.get("payload_json")));
        if ("skipped".equals(importRow(tenantId, row))) {
          skipped++;
        } else {
          success++;
        }
      } catch (DuplicateKeyException ex) {
        skipped++;
      } catch (BusinessException ex) {
        failed++;
        error = appendError(error, ex.getMessage());
      }
    }
    failed += jdbcTemplate.queryForObject(
        "select count(*) from import_preview_item where tenant_id = ? and job_id = ? and status = 'failed'",
        Integer.class, tenantId, jobId);
    String finalStatus = failed == 0 ? "success" : (success == 0 ? "failed" : "partial_success");
    jdbcTemplate.update("update import_job set status = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, error_message = coalesce(?, error_message), preview_confirmed_at = current_timestamp, finished_at = current_timestamp, updated_at = current_timestamp where id = ? and tenant_id = ?",
        finalStatus, success, failed, skipped, error, jobId, tenantId);
    auditService.record("CONFIRM_FINANCE_RECORD", "import_job", String.valueOf(jobId), "success=" + success + ",skipped=" + skipped);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> retryPreviewErrors(Long jobId, Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "reconciliation:run");
    Long tenantId = principal.getTenantId();
    getJob(jobId, tenantId);
    Object rawRows = request == null ? null : request.get("rows");
    if (!(rawRows instanceof List)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "rows 必须是数组");
    int retried = 0;
    int succeeded = 0;
    for (Object element : (List<?>) rawRows) {
      if (!(element instanceof Map)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "失败行数据格式不正确");
      Map<?, ?> input = (Map<?, ?>) element;
      int rowNo = integer(input.get("row_no"));
      Map<String, Object> existing = findPreviewItem(tenantId, jobId, rowNo);
      if (!"failed".equals(existing.get("status"))) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行不是失败行");
      retried++;
      Map<String, Object> merged = new LinkedHashMap<>(readPayload(existing.get("payload_json")));
      for (Map.Entry<?, ?> entry : input.entrySet()) {
        if (!"row_no".equals(String.valueOf(entry.getKey()))) {
          merged.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
      try {
        CsvFinanceRecordRow row = rowFromPayload(rowNo, merged);
        validateAmount(row);
        jdbcTemplate.update("update import_preview_item set payload_json = ?, status = 'retry_success', error_message = null, updated_at = current_timestamp where id = ? and tenant_id = ?",
            writePayload(toPayload(row)), existing.get("id"), tenantId);
        succeeded++;
        jdbcTemplate.update("delete from import_row_error where tenant_id = ? and import_job_id = ? and row_no = ?", tenantId, jobId, rowNo);
      } catch (BusinessException ex) {
        jdbcTemplate.update("update import_preview_item set status = 'failed', error_message = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
            ex.getMessage(), existing.get("id"), tenantId);
        jdbcTemplate.update("delete from import_row_error where tenant_id = ? and import_job_id = ? and row_no = ?", tenantId, jobId, rowNo);
        insertImportError(tenantId, jobId, new CsvRowError(rowNo, "row", ex.getMessage(), writePayload(merged)));
      }
    }
    jdbcTemplate.update("update import_job set status = 'preview_pending', failed_rows = (select count(*) from import_preview_item where tenant_id = ? and job_id = ? and status = 'failed'), success_rows = (select count(*) from import_preview_item where tenant_id = ? and job_id = ? and status in ('valid','retry_success')), updated_at = current_timestamp where id = ? and tenant_id = ?",
        tenantId, jobId, tenantId, jobId, jobId, tenantId);
    auditService.record("RETRY_FINANCE_RECORD_ERRORS", "import_job", String.valueOf(jobId), "retried=" + retried + ",succeeded=" + succeeded);
    return getPreview(jobId, tenantId);
  }

  public Map<String, Object> getPreview(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return getPreview(jobId, principal.getTenantId());
  }

  private Map<String, Object> getPreview(Long jobId, Long tenantId) {
    Map<String, Object> result = getJob(jobId, tenantId);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, row_no, status, error_message, payload_json from import_preview_item where tenant_id = ? and job_id = ? order by row_no",
        tenantId, jobId);
    for (Map<String, Object> row : rows) {
      row.put("payload", readPayload(row.remove("payload_json")));
    }
    result.put("preview_rows", rows);
    result.put("error_details", listErrors(jobId, tenantId));
    return result;
  }

  private Map<String, Object> toPayload(CsvFinanceRecordRow row) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("record_no", row.getRecordNo());
    payload.put("record_type", row.getRecordType());
    payload.put("record_date", row.getRecordDate() == null ? null : row.getRecordDate().toString());
    payload.put("posting_date", row.getPostingDate() == null ? null : row.getPostingDate().toString());
    payload.put("counterparty_name", row.getCounterpartyName());
    payload.put("amount", row.getAmount() == null ? null : row.getAmount().toPlainString());
    payload.put("summary", row.getSummary());
    payload.put("source_system", row.getSourceSystem());
    payload.put("contract_no", row.getContractNo());
    payload.put("project_no", row.getProjectNo());
    payload.put("subject", row.getSubject());
    payload.put("remark", row.getRemark());
    return payload;
  }

  private CsvFinanceRecordRow rowFromPayload(int rowNo, Map<String, Object> payload) {
    String recordNo = requiredText(payload.get("record_no"), rowNo, "record_no");
    String recordType = requiredText(payload.get("record_type"), rowNo, "record_type").toLowerCase();
    if (!recordType.equals("receipt") && !recordType.equals("payment") && !recordType.equals("voucher") && !recordType.equals("journal")) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 record_type 不支持");
    }
    LocalDate recordDate = parseDate(requiredText(payload.get("record_date"), rowNo, "record_date"), rowNo, "record_date");
    String postingDateText = textOrNull(payload.get("posting_date"));
    LocalDate postingDate = postingDateText == null ? null : parseDate(postingDateText, rowNo, "posting_date");
    BigDecimal amount = positiveDecimal(payload.get("amount"), rowNo);
    return new CsvFinanceRecordRow(rowNo, recordNo, recordType, recordDate, postingDate,
        textOrNull(payload.get("counterparty_name")), amount, textOrNull(payload.get("summary")),
        textOrNull(payload.get("source_system")), textOrNull(payload.get("contract_no")),
        textOrNull(payload.get("project_no")), textOrNull(payload.get("subject")),
        textOrNull(payload.get("remark")), writePayload(payload));
  }

  private void insertPreviewItem(Long tenantId, Long jobId, int rowNo, String status, String payloadJson, String errorMessage) {
    jdbcTemplate.update("insert into import_preview_item (tenant_id, job_id, row_no, status, payload_json, error_message) values (?, ?, ?, ?, ?, ?)",
        tenantId, jobId, rowNo, status, payloadJson, errorMessage);
  }

  private Map<String, Object> findPreviewItem(Long tenantId, Long jobId, int rowNo) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, row_no, status, payload_json from import_preview_item where tenant_id = ? and job_id = ? and row_no = ?",
        tenantId, jobId, rowNo);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "预览行不存在");
    return rows.get(0);
  }

  private Map<String, Object> readPayload(Object payloadJson) {
    if (payloadJson == null) return new LinkedHashMap<>();
    try {
      return objectMapper.readValue(String.valueOf(payloadJson), LinkedHashMap.class);
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "预览行数据无法解析");
    }
  }

  private String writePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "预览行数据序列化失败");
    }
  }

  private void requirePermission(AuthPrincipal principal, String code) {
    if (!permissionRepository.findPermissionCodes(principal.getTenantId(), principal.getRoles()).contains(code)) {
      throw new BusinessException(ErrorCode.PERMISSION_DENIED, "没有执行该操作的权限");
    }
  }

  private int integer(Object value) {
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "row_no 格式不正确");
    }
  }

  private String textOrNull(Object value) {
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private String requiredText(Object value, int rowNo, String field) {
    String text = textOrNull(value);
    if (text == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 不能为空");
    return text;
  }

  private BigDecimal positiveDecimal(Object value, int rowNo) {
    String text = requiredText(value, rowNo, "amount");
    try {
      BigDecimal amount = new BigDecimal(CsvImportSupport.normalizeAmount(text));
      if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 必须大于 0");
      return amount;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 格式错误");
    }
  }

  private LocalDate parseDate(String value, int rowNo, String field) {
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 日期格式错误");
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
        "insert into finance_record (tenant_id, record_no, record_type, record_date, posting_date, counterparty_name, amount, summary, source_system, subject, status, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)",
        tenantId, row.getRecordNo(), row.getRecordType(), Date.valueOf(row.getRecordDate()),
        row.getPostingDate() == null ? null : Date.valueOf(row.getPostingDate()), row.getCounterpartyName(),
        row.getAmount(), appendRemark(row.getSummary(), row.getRemark()), row.getSourceSystem(), row.getSubject(),
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
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message, started_at, finished_at, preview_confirmed_at from import_job where id = ? and tenant_id = ? and deleted_at is null",
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
