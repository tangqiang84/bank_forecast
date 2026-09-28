package com.bankforecast.receipt;

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
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
public class ReceiptImportService {
  private final JdbcTemplate jdbcTemplate;
  private final CsvReceiptParser parser;
  private final AuditService auditService;
  private final PermissionRepository permissionRepository;
  private final ObjectMapper objectMapper;
  private final int maxRows;
  private final long maxFileSize;
  private final BigDecimal maxAmount;

  public ReceiptImportService(JdbcTemplate jdbcTemplate, CsvReceiptParser parser, AuditService auditService,
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
  public Map<String, Object> importReceipts(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    String fileName = validateFile(file);
    Long jobId = createJob(principal.getTenantId(), fileName);
    int success = 0;
    int failed = 0;
    int skipped = 0;
    String error = null;
    CsvParseResult<CsvReceiptRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertImportError(principal.getTenantId(), jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvReceiptRow row : parsed.getRows()) {
      try {
        if ("skipped".equals(importRow(principal.getTenantId(), jobId, row))) {
          skipped++;
        } else {
          success++;
        }
      } catch (DuplicateKeyException ex) {
        skipped++;
      } catch (BusinessException ex) {
        failed++;
        insertImportError(principal.getTenantId(), jobId,
            new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson()));
        error = appendError(error, ex.getMessage());
      }
    }
    String status = failed == 0 ? "success" : (success == 0 ? "failed" : "partial_success");
    finishJob(jobId, parsed.getTotalRows(), success, failed, skipped, status, error);
    auditService.record("IMPORT_RECEIPT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    return result;
  }

  private String importRow(Long tenantId, Long jobId, CsvReceiptRow row) {
    if (receiptExists(tenantId, row.getReceiptNo())) {
      return "skipped";
    }
    Long transactionId = null;
    Long bankAccountId = null;
    if (row.getTransactionNo() != null) {
      Map<String, Object> transaction = findTransaction(tenantId, row.getTransactionNo());
      if (transaction == null) {
        throw new BusinessException(ErrorCode.ROW_DATA_ERROR,
            "第 " + row.getRowNo() + " 行交易流水号 " + row.getTransactionNo() + " 未匹配到已导入流水");
      }
      transactionId = ((Number) transaction.get("id")).longValue();
      bankAccountId = ((Number) transaction.get("bank_account_id")).longValue();
    }
    insertReceipt(tenantId, jobId, transactionId, bankAccountId, row);
    return "success";
  }

  @Transactional
  public Map<String, Object> previewReceipts(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    String fileName = validateFile(file);
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId, fileName);
    int valid = 0;
    int failed = 0;
    String error = null;
    CsvParseResult<CsvReceiptRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertPreviewItem(tenantId, jobId, rowError.getRowNo(), "failed", rowError.getRawJson(), rowError.getMessage());
      insertImportError(tenantId, jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvReceiptRow row : parsed.getRows()) {
      try {
        validateAmount(row.getAmount(), row.getRowNo());
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
    auditService.record("PREVIEW_RECEIPT", "import_job", String.valueOf(jobId), "file=" + fileName);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> confirmPreview(Long jobId) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "receipt:import");
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
        CsvReceiptRow row = rowFromPayload(rowNo, readPayload(item.get("payload_json")));
        if ("skipped".equals(importRow(tenantId, jobId, row))) {
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
    auditService.record("CONFIRM_RECEIPT", "import_job", String.valueOf(jobId), "success=" + success + ",skipped=" + skipped);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> retryPreviewErrors(Long jobId, Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "receipt:import");
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
        CsvReceiptRow row = rowFromPayload(rowNo, merged);
        validateAmount(row.getAmount(), rowNo);
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
    auditService.record("RETRY_RECEIPT_ERRORS", "import_job", String.valueOf(jobId), "retried=" + retried + ",succeeded=" + succeeded);
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

  private Map<String, Object> toPayload(CsvReceiptRow row) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("bank_name", row.getBankName());
    payload.put("receipt_no", row.getReceiptNo());
    payload.put("print_date", row.getPrintDate() == null ? null : row.getPrintDate().toString());
    payload.put("transaction_date", row.getTransactionDate() == null ? null : row.getTransactionDate().toString());
    payload.put("transaction_time", row.getTransactionTime());
    payload.put("currency", row.getCurrency());
    payload.put("payer_name", row.getPayerName());
    payload.put("payer_account_last4", row.getPayerAccountLast4());
    payload.put("payee_name", row.getPayeeName());
    payload.put("payee_account_last4", row.getPayeeAccountLast4());
    payload.put("payer_bank", row.getPayerBank());
    payload.put("payee_bank", row.getPayeeBank());
    payload.put("amount", row.getAmount() == null ? null : row.getAmount().toPlainString());
    payload.put("summary", row.getSummary());
    payload.put("transaction_no", row.getTransactionNo());
    payload.put("channel", row.getChannel());
    payload.put("verification_code", row.getVerificationCode());
    return payload;
  }

  private CsvReceiptRow rowFromPayload(int rowNo, Map<String, Object> payload) {
    String currency = textOrNull(payload.get("currency"));
    if (currency == null) currency = "CNY";
    return new CsvReceiptRow(rowNo,
        textOrNull(payload.get("bank_name")),
        requiredText(payload.get("receipt_no"), rowNo, "receipt_no"),
        parseDate(textOrNull(payload.get("print_date")), rowNo, "print_date"),
        parseDate(requiredText(payload.get("transaction_date"), rowNo, "transaction_date"), rowNo, "transaction_date"),
        textOrNull(payload.get("transaction_time")),
        currency,
        textOrNull(payload.get("payer_name")),
        textOrNull(payload.get("payer_account_last4")),
        textOrNull(payload.get("payee_name")),
        textOrNull(payload.get("payee_account_last4")),
        textOrNull(payload.get("payer_bank")),
        textOrNull(payload.get("payee_bank")),
        positiveDecimal(payload.get("amount"), rowNo),
        textOrNull(payload.get("summary")),
        textOrNull(payload.get("transaction_no")),
        textOrNull(payload.get("channel")),
        textOrNull(payload.get("verification_code")),
        writePayload(payload));
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

  private boolean receiptExists(Long tenantId, String receiptNo) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from receipt where tenant_id = ? and receipt_no = ? and deleted_at is null",
        Integer.class, tenantId, receiptNo);
    return count != null && count > 0;
  }

  private Map<String, Object> findTransaction(Long tenantId, String transactionNo) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, bank_account_id from bank_transaction where tenant_id = ? and transaction_no = ? and deleted_at is null order by id",
        tenantId, transactionNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Long insertReceipt(Long tenantId, Long jobId, Long transactionId, Long bankAccountId, CsvReceiptRow row) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into receipt (tenant_id, import_job_id, bank_transaction_id, bank_account_id, bank_name, receipt_no, print_date, transaction_date, transaction_time, currency, payer_name, payer_account_last4, payee_name, payee_account_last4, payer_bank, payee_bank, amount, summary, transaction_no, channel, verification_code) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setLong(2, jobId);
      if (transactionId == null) ps.setObject(3, null); else ps.setLong(3, transactionId);
      if (bankAccountId == null) ps.setObject(4, null); else ps.setLong(4, bankAccountId);
      ps.setString(5, row.getBankName());
      ps.setString(6, row.getReceiptNo());
      ps.setDate(7, row.getPrintDate() == null ? null : Date.valueOf(row.getPrintDate()));
      ps.setDate(8, Date.valueOf(row.getTransactionDate()));
      ps.setString(9, row.getTransactionTime());
      ps.setString(10, row.getCurrency());
      ps.setString(11, row.getPayerName());
      ps.setString(12, row.getPayerAccountLast4());
      ps.setString(13, row.getPayeeName());
      ps.setString(14, row.getPayeeAccountLast4());
      ps.setString(15, row.getPayerBank());
      ps.setString(16, row.getPayeeBank());
      ps.setBigDecimal(17, row.getAmount());
      ps.setString(18, row.getSummary());
      ps.setString(19, row.getTransactionNo());
      ps.setString(20, row.getChannel());
      ps.setString(21, row.getVerificationCode());
      return ps;
    }, keyHolder);
    return generatedId(keyHolder);
  }

  @SuppressWarnings("unchecked")
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

  private String validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "receipts.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    return fileName;
  }

  private void validateAmount(BigDecimal amount, int rowNo) {
    if (amount.scale() > 2 || amount.compareTo(maxAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 超出金额边界");
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
      if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
      return amount;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额格式错误");
    }
  }

  private LocalDate parseDate(String value, int rowNo, String field) {
    if (value == null) return null;
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); }
      catch (DateTimeParseException ignored) {
        // 尝试下一种回单导出日期格式
      }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 日期格式错误");
  }

  public Map<String, Object> getJob(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return getJob(jobId, principal.getTenantId());
  }

  private Long createJob(Long tenantId, String fileName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into import_job (tenant_id, job_type, source_type, file_name, status, started_at) values (?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setString(2, "receipt"); ps.setString(3, "file"); ps.setString(4, fileName);
      ps.setString(5, "running"); ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now())); return ps;
    }, keyHolder);
    return generatedId(keyHolder);
  }

  private void finishJob(Long jobId, int total, int success, int failed, int skipped, String status, String error) {
    jdbcTemplate.update("update import_job set status = ?, total_rows = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, error_message = ?, finished_at = ?, updated_at = ? where id = ?",
        status, total, success, failed, skipped, error, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), jobId);
  }

  private Map<String, Object> getJob(Long jobId, Long tenantId) {
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message, started_at, finished_at, preview_confirmed_at from import_job where id = ? and tenant_id = ? and deleted_at is null",
        jobId, tenantId);
    if (items.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入任务不存在");
    Map<String, Object> result = new LinkedHashMap<>(items.get(0));
    result.put("error_details", listErrors(jobId, tenantId));
    return result;
  }

  public List<Map<String, Object>> listErrors(Long jobId) {
    AuthPrincipal principal = requireAuth();
    getJob(jobId, principal.getTenantId());
    return listErrors(jobId, principal.getTenantId());
  }

  private List<Map<String, Object>> listErrors(Long jobId, Long tenantId) {
    return jdbcTemplate.queryForList(
        "select row_no, field_name, raw_json, error_message from import_row_error where import_job_id = ? and tenant_id = ? order by row_no",
        jobId, tenantId);
  }

  private void insertImportError(Long tenantId, Long jobId, CsvRowError error) {
    jdbcTemplate.update(
        "insert into import_row_error (tenant_id, import_job_id, row_no, field_name, raw_json, error_message) values (?, ?, ?, ?, ?, ?)",
        tenantId, jobId, error.getRowNo(), error.getField(), error.getRawJson(), error.getMessage());
  }

  private String appendError(String current, String next) {
    if (current == null) return next;
    return current.length() > 900 ? current : current + "；" + next;
  }

  private java.io.InputStream open(MultipartFile file) {
    try { return file.getInputStream(); }
    catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件读取失败"); }
  }

  private Long generatedId(KeyHolder keyHolder) {
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
      return ((Number) keyHolder.getKeys().get("id")).longValue();
    }
    return keyHolder.getKey().longValue();
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
