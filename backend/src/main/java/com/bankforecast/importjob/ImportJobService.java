package com.bankforecast.importjob;

import com.bankforecast.audit.AuditService;
import com.bankforecast.bank.BankAccountRepository;
import com.bankforecast.bank.BankTemplateMappingService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.PermissionRepository;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
public class ImportJobService {

  private final JdbcTemplate jdbcTemplate;
  private final CsvBankStatementParser parser;
  private final ExcelBankStatementParser excelParser;
  private final BankAccountRepository bankAccountRepository;
  private final BankTemplateMappingService templateMappingService;
  private final AuditService auditService;
  private final PermissionRepository permissionRepository;
  private final int maxRows;
  private final long maxFileSize;
  private final int maxDateRangeDays;
  private final BigDecimal maxAmount;

  public ImportJobService(JdbcTemplate jdbcTemplate, CsvBankStatementParser parser, ExcelBankStatementParser excelParser,
      BankAccountRepository bankAccountRepository, BankTemplateMappingService templateMappingService,
      AuditService auditService, PermissionRepository permissionRepository,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize,
      @Value("${bank-forecast.import.max-date-range-days}") int maxDateRangeDays,
      @Value("${bank-forecast.import.max-amount}") BigDecimal maxAmount) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.excelParser = excelParser;
    this.bankAccountRepository = bankAccountRepository;
    this.templateMappingService = templateMappingService;
    this.auditService = auditService;
    this.permissionRepository = permissionRepository;
    this.maxRows = maxRows;
    this.maxFileSize = maxFileSize;
    this.maxDateRangeDays = maxDateRangeDays;
    this.maxAmount = maxAmount;
  }

  @Transactional
  public Map<String, Object> importBankStatements(Long bankAccountId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    }
    if (file.getSize() > maxFileSize) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    }
    String fileName = file.getOriginalFilename() == null ? "bank_statement.csv" : file.getOriginalFilename();
    String lowerFileName = fileName.toLowerCase();
    if (!lowerFileName.endsWith(".csv") && !lowerFileName.endsWith(".xlsx")) {
      throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 CSV 或 XLSX 文件导入");
    }
    if (!bankAccountRepository.existsByTenant(principal.getTenantId(), bankAccountId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
    }

    Long jobId = createJob(principal.getTenantId(), fileName);
    int successRows = 0;
    int failedRows = 0;
    int skippedRows = 0;
    String errorMessage = null;

    ParsedFile parsedFile = parseFile(file, lowerFileName, templateMappingService.aliases(principal.getTenantId()));
    CsvParseResult<CsvBankStatementRow> parsed = parsedFile.result;
    List<Map<String, Object>> templates = parsedFile.templates;
    for (CsvRowError rowError : parsed.getErrors()) {
      failedRows++;
      insertRawError(principal.getTenantId(), jobId, bankAccountId, rowError);
      insertImportError(principal.getTenantId(), jobId, rowError);
      errorMessage = appendError(errorMessage, rowError.getMessage());
    }
    Map<String, Integer> seenFingerprints = new HashMap<>();
    int duplicateSuspectedRows = 0;
    for (CsvBankStatementRow row : parsed.getRows()) {
      try {
        validateDate(row);
        validateAmount(row.getAmount(), row.getRowNo(), "amount");
        Integer strongDup = jdbcTemplate.queryForObject(
            "select count(*) from bank_transaction where tenant_id = ? and bank_account_id = ? and transaction_no = ? and deleted_at is null",
            Integer.class, principal.getTenantId(), bankAccountId, row.getTransactionNo());
        if (strongDup != null && strongDup > 0) {
          skippedRows++;
          insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "skipped", "重复流水号");
          continue;
        }
        String fingerprint = WeakFingerprint.of(row);
        String duplicateMessage = detectDuplicate(
            principal.getTenantId(), bankAccountId, fingerprint, seenFingerprints);
        seenFingerprints.putIfAbsent(fingerprint, row.getRowNo());
        if (duplicateMessage != null) {
          duplicateSuspectedRows++;
          insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "skipped", duplicateMessage);
          continue;
        }
        insertTransaction(principal.getTenantId(), jobId, bankAccountId, row);
        insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "success", null);
        successRows++;
      } catch (DuplicateKeyException ex) {
        skippedRows++;
        insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "skipped", "重复流水号");
      } catch (BusinessException ex) {
        failedRows++;
        insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "failed", ex.getMessage());
        insertImportError(principal.getTenantId(), jobId,
            new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson()));
        errorMessage = appendError(errorMessage, ex.getMessage());
      }
    }

    String status = failedRows == 0 ? "success" : (successRows == 0 ? "failed" : "partial_success");
    finishJob(jobId, parsed.getTotalRows(), successRows, failedRows, skippedRows, duplicateSuspectedRows, status, errorMessage);
    auditService.record("IMPORT_BANK_STATEMENT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    result.put("file_format", lowerFileName.endsWith(".xlsx") ? "xlsx" : "csv");
    if (!templates.isEmpty()) result.put("recognized_templates", templates);
    return result;
  }

  @Transactional
  public Map<String, Object> previewBankStatements(Long bankAccountId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    validateFile(principal, bankAccountId, file);
    String fileName = file.getOriginalFilename() == null ? "bank_statement.csv" : file.getOriginalFilename();
    ParsedFile parsedFile = parseFile(file, fileName.toLowerCase(), templateMappingService.aliases(principal.getTenantId()));
    Long jobId = createJob(principal.getTenantId(), fileName);
    int failedRows = 0;
    String errorMessage = null;
    for (CsvRowError rowError : parsedFile.result.getErrors()) {
      failedRows++;
      insertPreviewError(principal.getTenantId(), jobId, bankAccountId, rowError);
      insertImportError(principal.getTenantId(), jobId, rowError);
      errorMessage = appendError(errorMessage, rowError.getMessage());
    }
    Map<String, Integer> seenFingerprints = new HashMap<>();
    int validRows = 0;
    int duplicateSuspectedRows = 0;
    for (CsvBankStatementRow row : parsedFile.result.getRows()) {
      try {
        validateRow(row);
        String fingerprint = WeakFingerprint.of(row);
        String duplicateMessage = detectDuplicate(
            principal.getTenantId(), bankAccountId, fingerprint, seenFingerprints);
        seenFingerprints.putIfAbsent(fingerprint, row.getRowNo());
        if (duplicateMessage != null) {
          duplicateSuspectedRows++;
          insertPreviewRow(principal.getTenantId(), jobId, bankAccountId, row,
              "duplicate_suspect", duplicateMessage, fingerprint);
        } else {
          validRows++;
          insertPreviewRow(principal.getTenantId(), jobId, bankAccountId, row, "valid", null, fingerprint);
        }
      } catch (BusinessException ex) {
        failedRows++;
        insertPreviewRow(principal.getTenantId(), jobId, bankAccountId, row, "failed", ex.getMessage(), null);
        insertImportError(principal.getTenantId(), jobId,
            new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson()));
        errorMessage = appendError(errorMessage, ex.getMessage());
      }
    }
    String status = (validRows == 0 && duplicateSuspectedRows == 0) ? "preview_failed" : "preview_pending";
    finishJob(jobId, parsedFile.result.getTotalRows(), validRows, failedRows, 0, duplicateSuspectedRows, status, errorMessage);
    auditService.record("PREVIEW_BANK_STATEMENT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getPreview(jobId, principal.getTenantId());
    result.put("file_format", fileName.toLowerCase().endsWith(".xlsx") ? "xlsx" : "csv");
    result.put("recognized_templates", parsedFile.templates);
    return result;
  }

  public Map<String, Object> getPreview(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return getPreview(jobId, principal.getTenantId());
  }

  @Transactional
  public Map<String, Object> confirmPreview(Long jobId) {
    return confirmPreview(jobId, null);
  }

  /** include_duplicates：用户勾选强制导入的疑似重复行号（预览行 row_no）。 */
  @Transactional
  public Map<String, Object> confirmPreview(Long jobId, Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "transaction:import");
    Map<String, Object> job = getJob(jobId, principal.getTenantId());
    String status = String.valueOf(job.get("status"));
    if (!"preview_pending".equals(status) && !"preview_failed".equals(status)) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "当前导入任务不是待确认预览状态");
    }
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select * from import_preview_row where tenant_id = ? and import_job_id = ? and status in ('valid','retry_success') and deleted_at is null order by row_no",
        principal.getTenantId(), jobId);
    int success = 0;
    int skipped = 0;
    String errorMessage = null;
    for (Map<String, Object> row : rows) {
      CsvBankStatementRow statement = previewToRow(row);
      try {
        insertTransaction(principal.getTenantId(), jobId, ((Number) row.get("bank_account_id")).longValue(), statement);
        insertRaw(principal.getTenantId(), jobId, ((Number) row.get("bank_account_id")).longValue(), statement, "success", null);
        jdbcTemplate.update("update import_preview_row set status = 'confirmed', updated_at = current_timestamp where id = ? and tenant_id = ?", row.get("id"), principal.getTenantId());
        success++;
      } catch (DuplicateKeyException ex) {
        insertRaw(principal.getTenantId(), jobId, ((Number) row.get("bank_account_id")).longValue(), statement, "skipped", "重复流水号");
        jdbcTemplate.update("update import_preview_row set status = 'confirmed', updated_at = current_timestamp where id = ? and tenant_id = ?", row.get("id"), principal.getTenantId());
        skipped++;
      } catch (BusinessException ex) {
        errorMessage = appendError(errorMessage, ex.getMessage());
      }
    }
    int forcedDuplicates = 0;
    for (Integer rowNo : parseIncludeDuplicates(request)) {
      Map<String, Object> suspect = findPreviewRow(jobId, principal.getTenantId(), rowNo);
      if (!"duplicate_suspect".equals(suspect.get("status"))) {
        throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行不是疑似重复行");
      }
      CsvBankStatementRow statement = previewToRow(suspect);
      Long bankAccountId = ((Number) suspect.get("bank_account_id")).longValue();
      try {
        insertTransaction(principal.getTenantId(), jobId, bankAccountId, statement);
        insertRaw(principal.getTenantId(), jobId, bankAccountId, statement, "success", "疑似重复人工确认导入");
        success++;
        forcedDuplicates++;
      } catch (DuplicateKeyException ex) {
        insertRaw(principal.getTenantId(), jobId, bankAccountId, statement, "skipped", "重复流水号");
        skipped++;
      }
      jdbcTemplate.update("update import_preview_row set status = 'confirmed', updated_at = current_timestamp where id = ? and tenant_id = ?", suspect.get("id"), principal.getTenantId());
    }
    int failed = jdbcTemplate.queryForObject("select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and status = 'failed' and deleted_at is null", Integer.class, principal.getTenantId(), jobId);
    String finalStatus = failed == 0 ? "success" : (success == 0 ? "failed" : "partial_success");
    jdbcTemplate.update("update import_job set status = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, error_message = coalesce(?, error_message), preview_confirmed_at = current_timestamp, finished_at = current_timestamp, updated_at = current_timestamp where id = ? and tenant_id = ?", finalStatus, success, failed, skipped, errorMessage, jobId, principal.getTenantId());
    auditService.record("CONFIRM_BANK_STATEMENT", "import_job", String.valueOf(jobId), "success=" + success + ",skipped=" + skipped + ",forced_duplicates=" + forcedDuplicates);
    return getPreview(jobId, principal.getTenantId());
  }

  private java.util.Set<Integer> parseIncludeDuplicates(Map<String, Object> request) {
    java.util.Set<Integer> result = new java.util.LinkedHashSet<>();
    if (request == null || request.get("include_duplicates") == null) return result;
    Object raw = request.get("include_duplicates");
    if (!(raw instanceof List)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "include_duplicates 必须是行号数组");
    for (Object item : (List<?>) raw) result.add(integer(item, "include_duplicates"));
    return result;
  }

  @Transactional
  public Map<String, Object> retryPreviewErrors(Long jobId, Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "transaction:import");
    getJob(jobId, principal.getTenantId());
    Object rawRows = request == null ? null : request.get("rows");
    if (!(rawRows instanceof List)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "rows 必须是数组");
    int retried = 0;
    int succeeded = 0;
    for (Object item : (List<?>) rawRows) {
      if (!(item instanceof Map)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "失败行数据格式不正确");
      Map<?, ?> input = (Map<?, ?>) item;
      int rowNo = integer(input.get("row_no"), "row_no");
      Map<String, Object> existing = findPreviewRow(jobId, principal.getTenantId(), rowNo);
      if (!"failed".equals(existing.get("status"))) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行不是失败行");
      retried++;
      try {
        CsvBankStatementRow row = retryToRow(input, rowNo, existing.get("raw_json"));
        validateRow(row);
        String fingerprint = WeakFingerprint.of(row);
        Long bankAccountId = ((Number) existing.get("bank_account_id")).longValue();
        String duplicateMessage = detectRetryDuplicate(
            principal.getTenantId(), jobId, bankAccountId, ((Number) existing.get("id")).longValue(), fingerprint);
        updatePreviewRow(existing, row, duplicateMessage == null ? "retry_success" : "duplicate_suspect",
            duplicateMessage, fingerprint);
        succeeded++;
        jdbcTemplate.update("delete from import_row_error where tenant_id = ? and import_job_id = ? and row_no = ?", principal.getTenantId(), jobId, rowNo);
      } catch (BusinessException ex) {
        jdbcTemplate.update("update import_preview_row set status = 'failed', error_message = ?, updated_at = current_timestamp where id = ? and tenant_id = ?", ex.getMessage(), existing.get("id"), principal.getTenantId());
        jdbcTemplate.update("delete from import_row_error where tenant_id = ? and import_job_id = ? and row_no = ?", principal.getTenantId(), jobId, rowNo);
        insertImportError(principal.getTenantId(), jobId, new CsvRowError(rowNo, "row", ex.getMessage(), String.valueOf(existing.get("raw_json"))));
      }
    }
    jdbcTemplate.update("update import_job set status = 'preview_pending', failed_rows = (select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and status = 'failed' and deleted_at is null), success_rows = (select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and status in ('valid','retry_success')), duplicate_suspected_rows = (select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and status = 'duplicate_suspect' and deleted_at is null), updated_at = current_timestamp where id = ? and tenant_id = ?", principal.getTenantId(), jobId, principal.getTenantId(), jobId, principal.getTenantId(), jobId, jobId, principal.getTenantId());
    auditService.record("RETRY_BANK_STATEMENT_ERRORS", "import_job", String.valueOf(jobId), "retried=" + retried + ",succeeded=" + succeeded);
    return getPreview(jobId, principal.getTenantId());
  }

  private ParsedFile parseFile(MultipartFile file, String lowerFileName, Map<String, String> aliases) {
    CsvParseResult<CsvBankStatementRow> parsed;
    List<Map<String, Object>> templates = new ArrayList<>();
    if (lowerFileName.endsWith(".xlsx")) {
      ExcelBankStatementParser.ExcelParseResult excel = excelParser.parse(open(file), maxRows, aliases);
      parsed = new CsvParseResult<>(excel.getRows(), excel.getErrors(), excel.getTotalRows());
      templates = excel.getTemplates();
    } else {
      parsed = parser.parse(open(file), maxRows, aliases);
    }
    return new ParsedFile(parsed, templates);
  }

  private void validateFile(AuthPrincipal principal, Long bankAccountId, MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "bank_statement.csv" : file.getOriginalFilename().toLowerCase();
    if (!fileName.endsWith(".csv") && !fileName.endsWith(".xlsx")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 CSV 或 XLSX 文件导入");
    if (!bankAccountRepository.existsByTenant(principal.getTenantId(), bankAccountId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
  }

  private void validateRow(CsvBankStatementRow row) { validateDate(row); validateAmount(row.getAmount(), row.getRowNo(), "amount"); }

  private void insertPreviewRow(Long tenantId, Long jobId, Long bankAccountId, CsvBankStatementRow row, String status, String error, String fingerprint) {
    jdbcTemplate.update("insert into import_preview_row (tenant_id, import_job_id, bank_account_id, row_no, raw_json, transaction_no, transaction_date, direction, amount, balance_after, counterparty_name, summary, status, error_message, weak_fingerprint) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tenantId, jobId, bankAccountId, allocatePreviewRowNo(tenantId, jobId, row.getRowNo()), row.getRawJson(), row.getTransactionNo(), row.getTransactionDate() == null ? null : Date.valueOf(row.getTransactionDate()), row.getDirection(), row.getAmount(), row.getBalanceAfter(), row.getCounterpartyName(), row.getSummary(), status, error, fingerprint);
  }

  private void insertPreviewError(Long tenantId, Long jobId, Long bankAccountId, CsvRowError error) {
    jdbcTemplate.update("insert into import_preview_row (tenant_id, import_job_id, bank_account_id, row_no, raw_json, status, error_message) values (?, ?, ?, ?, ?, 'failed', ?)", tenantId, jobId, bankAccountId, allocatePreviewRowNo(tenantId, jobId, error.getRowNo()), error.getRawJson(), error.getMessage());
  }

  private int allocatePreviewRowNo(Long tenantId, Long jobId, int sourceRowNo) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and row_no = ? and deleted_at is null", Integer.class, tenantId, jobId, sourceRowNo);
    if (count == null || count == 0) return sourceRowNo;
    Integer max = jdbcTemplate.queryForObject("select coalesce(max(row_no), 0) from import_preview_row where tenant_id = ? and import_job_id = ? and deleted_at is null", Integer.class, tenantId, jobId);
    return (max == null ? sourceRowNo : max) + 1;
  }

  private Map<String, Object> getPreview(Long jobId, Long tenantId) {
    Map<String, Object> result = getJob(jobId, tenantId);
    result.put("preview_rows", jdbcTemplate.queryForList("select id, row_no, transaction_no, transaction_date, direction, amount, balance_after, counterparty_name, summary, status, error_message from import_preview_row where tenant_id = ? and import_job_id = ? and deleted_at is null order by row_no", tenantId, jobId));
    result.put("error_details", listErrors(jobId, tenantId));
    return result;
  }

  private Map<String, Object> findPreviewRow(Long jobId, Long tenantId, int rowNo) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from import_preview_row where tenant_id = ? and import_job_id = ? and row_no = ? and deleted_at is null", tenantId, jobId, rowNo);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "预览行不存在");
    return rows.get(0);
  }

  private CsvBankStatementRow previewToRow(Map<String, Object> row) {
    Object date = row.get("transaction_date");
    LocalDate transactionDate = date instanceof Date ? ((Date) date).toLocalDate() : LocalDate.parse(String.valueOf(date));
    return new CsvBankStatementRow(((Number) row.get("row_no")).intValue(), String.valueOf(row.get("transaction_no")), transactionDate, String.valueOf(row.get("direction")), (BigDecimal) row.get("amount"), (BigDecimal) row.get("balance_after"), (String) row.get("counterparty_name"), (String) row.get("summary"), String.valueOf(row.get("raw_json")));
  }

  private CsvBankStatementRow retryToRow(Map<?, ?> input, int rowNo, Object rawJson) {
    String transactionNo = text(input.get("transaction_no"));
    String dateText = text(input.get("transaction_date"));
    String direction = normalizeDirection(text(input.get("direction")));
    BigDecimal amount = decimal(text(input.get("amount")), "amount", rowNo);
    BigDecimal balance = text(input.get("balance_after")).isEmpty() ? null : decimal(text(input.get("balance_after")), "balance_after", rowNo);
    LocalDate date;
    try { date = LocalDate.parse(dateText); } catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误"); }
    if (transactionNo.isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 transaction_no 不能为空");
    if (dateText.isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 transaction_date 不能为空");
    return new CsvBankStatementRow(rowNo, transactionNo, date, direction, amount, balance, text(input.get("counterparty_name")), text(input.get("summary")), rawJson == null ? "{}" : String.valueOf(rawJson));
  }

  private void updatePreviewRow(Map<String, Object> existing, CsvBankStatementRow row, String status,
      String errorMessage, String fingerprint) {
    jdbcTemplate.update("update import_preview_row set raw_json = ?, transaction_no = ?, transaction_date = ?, direction = ?, amount = ?, balance_after = ?, counterparty_name = ?, summary = ?, status = ?, error_message = ?, weak_fingerprint = ?, updated_at = current_timestamp where id = ?", row.getRawJson(), row.getTransactionNo(), Date.valueOf(row.getTransactionDate()), row.getDirection(), row.getAmount(), row.getBalanceAfter(), row.getCounterpartyName(), row.getSummary(), status, errorMessage, fingerprint, existing.get("id"));
  }

  /** 预览/重试时的弱重复检测：先查本文件已见指纹，再查账户已有流水。 */
  private String detectDuplicate(Long tenantId, Long bankAccountId, String fingerprint,
      Map<String, Integer> seenFingerprints) {
    Integer priorRow = seenFingerprints.get(fingerprint);
    if (priorRow != null) return "与本文件第 " + priorRow + " 行疑似重复（弱指纹）";
    return detectDuplicateInDb(tenantId, bankAccountId, fingerprint);
  }

  private String detectRetryDuplicate(Long tenantId, Long jobId, Long bankAccountId, long selfId,
      String fingerprint) {
    Integer sameJob = jdbcTemplate.queryForObject(
        "select count(*) from import_preview_row where tenant_id = ? and import_job_id = ? and weak_fingerprint = ? and status in ('valid','retry_success','duplicate_suspect') and deleted_at is null and id <> ?",
        Integer.class, tenantId, jobId, fingerprint, selfId);
    if (sameJob != null && sameJob > 0) return "与本任务其他行疑似重复（弱指纹）";
    return detectDuplicateInDb(tenantId, bankAccountId, fingerprint);
  }

  private String detectDuplicateInDb(Long tenantId, Long bankAccountId, String fingerprint) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and bank_account_id = ? and weak_fingerprint = ? and deleted_at is null",
        Integer.class, tenantId, bankAccountId, fingerprint);
    if (count != null && count > 0) return "与账户已有流水疑似重复（弱指纹）";
    return null;
  }

  private int integer(Object value, String field) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, field + " 格式不正确"); } }
  private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private BigDecimal decimal(String value, String field, int rowNo) { try { return new BigDecimal(value.replace(",", "").trim()); } catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 格式错误"); } }
  private String normalizeDirection(String value) { String v = value.toLowerCase(); if ("income".equals(v) || "收入".equals(v) || "in".equals(v) || "credit".equals(v) || "贷方".equals(v)) return "income"; if ("expense".equals(v) || "支出".equals(v) || "out".equals(v) || "debit".equals(v) || "借方".equals(v)) return "expense"; if ("transfer".equals(v) || "内部转账".equals(v)) return "transfer"; if ("refund".equals(v) || "退款".equals(v)) return "refund"; if ("reversal".equals(v) || "冲正".equals(v)) return "reversal"; throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "交易方向不合法"); }

  private static class ParsedFile { private final CsvParseResult<CsvBankStatementRow> result; private final List<Map<String, Object>> templates; private ParsedFile(CsvParseResult<CsvBankStatementRow> result, List<Map<String, Object>> templates) { this.result = result; this.templates = templates; } }

  private void validateDate(CsvBankStatementRow row) {
    long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(row.getTransactionDate(), java.time.LocalDate.now()));
    if (days > maxDateRangeDays) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行交易日期超出允许范围");
    }
  }

  private void validateAmount(BigDecimal amount, int rowNo, String field) {
    if (amount.scale() > 2 || amount.compareTo(maxAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 超出金额边界");
    }
  }

  public Map<String, Object> getJob(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return getJob(jobId, principal.getTenantId());
  }

  /** 银行流水导入质量汇总（P2-3）：近 N 天任务级指标和成功率/疑似重复率。 */
  public Map<String, Object> qualitySummary(Integer days) {
    AuthPrincipal principal = requireAuth();
    int windowDays = days == null || days <= 0 ? 30 : Math.min(days, 365);
    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select count(*) as job_count, coalesce(sum(total_rows), 0) as total_rows, coalesce(sum(success_rows), 0) as success_rows, coalesce(sum(failed_rows), 0) as failed_rows, coalesce(sum(skipped_rows), 0) as skipped_rows, coalesce(sum(duplicate_suspected_rows), 0) as duplicate_suspected_rows "
            + "from import_job where tenant_id = ? and job_type = 'bank_statement' and deleted_at is null and created_at >= ?",
        principal.getTenantId(), Timestamp.valueOf(LocalDateTime.now().minusDays(windowDays)));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("days", windowDays);
    result.put("job_count", row.get("job_count"));
    result.put("total_rows", row.get("total_rows"));
    result.put("success_rows", row.get("success_rows"));
    result.put("failed_rows", row.get("failed_rows"));
    result.put("skipped_rows", row.get("skipped_rows"));
    result.put("duplicate_suspected_rows", row.get("duplicate_suspected_rows"));
    long total = ((Number) row.get("total_rows")).longValue();
    long success = ((Number) row.get("success_rows")).longValue();
    long suspected = ((Number) row.get("duplicate_suspected_rows")).longValue();
    result.put("success_rate", total == 0 ? null
        : BigDecimal.valueOf(success).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP));
    result.put("duplicate_suspect_rate", total == 0 ? null
        : BigDecimal.valueOf(suspected).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP));
    return result;
  }

  public String getJobType(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return String.valueOf(getJob(jobId, principal.getTenantId()).get("job_type"));
  }

  private void requirePermission(AuthPrincipal principal, String code) {
    if (!permissionRepository.findPermissionCodes(principal.getTenantId(), principal.getRoles()).contains(code)) {
      throw new BusinessException(ErrorCode.PERMISSION_DENIED, "没有执行该操作的权限");
    }
  }

  private Long createJob(Long tenantId, String fileName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into import_job (tenant_id, job_type, source_type, file_name, status, started_at) values (?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setString(2, "bank_statement");
      ps.setString(3, "file");
      ps.setString(4, fileName);
      ps.setString(5, "running");
      ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
      return ps;
    }, keyHolder);
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
      return ((Number) keyHolder.getKeys().get("id")).longValue();
    }
    return keyHolder.getKey().longValue();
  }

  private void finishJob(Long jobId, int totalRows, int successRows, int failedRows, int skippedRows,
      int duplicateSuspectedRows, String status, String errorMessage) {
    jdbcTemplate.update(
        "update import_job set status = ?, total_rows = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, duplicate_suspected_rows = ?, error_message = ?, finished_at = ?, updated_at = ? where id = ?",
        status,
        totalRows,
        successRows,
        failedRows,
        skippedRows,
        duplicateSuspectedRows,
        errorMessage,
        Timestamp.valueOf(LocalDateTime.now()),
        Timestamp.valueOf(LocalDateTime.now()),
        jobId);
  }

  private void insertRaw(Long tenantId, Long jobId, Long bankAccountId, CsvBankStatementRow row,
      String status, String error) {
    jdbcTemplate.update(
        "insert into bank_statement_raw (tenant_id, import_job_id, bank_account_id, row_no, raw_json, parse_status, parse_error) values (?, ?, ?, ?, ?, ?, ?)",
        tenantId,
        jobId,
        bankAccountId,
        row.getRowNo(),
        row.getRawJson(),
        status,
        error);
  }

  private void insertRawError(Long tenantId, Long jobId, Long bankAccountId, CsvRowError error) {
    jdbcTemplate.update(
        "insert into bank_statement_raw (tenant_id, import_job_id, bank_account_id, row_no, raw_json, parse_status, parse_error) values (?, ?, ?, ?, ?, ?, ?)",
        tenantId, jobId, bankAccountId, error.getRowNo(), error.getRawJson(), "failed", error.getMessage());
  }

  private void insertImportError(Long tenantId, Long jobId, CsvRowError error) {
    jdbcTemplate.update(
        "insert into import_row_error (tenant_id, import_job_id, row_no, field_name, raw_json, error_message) values (?, ?, ?, ?, ?, ?)",
        tenantId, jobId, error.getRowNo(), error.getField(), error.getRawJson(), error.getMessage());
  }

  private void insertTransaction(Long tenantId, Long jobId, Long bankAccountId, CsvBankStatementRow row) {
    jdbcTemplate.update(
        "insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, booking_date, direction, amount, balance_after, counterparty_name, summary, match_status, weak_fingerprint) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenantId,
        bankAccountId,
        jobId,
        row.getTransactionNo(),
        Date.valueOf(row.getTransactionDate()),
        Date.valueOf(row.getTransactionDate()),
        row.getDirection(),
        row.getAmount(),
        row.getBalanceAfter(),
        row.getCounterpartyName(),
        row.getSummary(),
        "unmatched",
        WeakFingerprint.of(row));
    jdbcTemplate.update("update bank_account set last_transaction_at = case when last_transaction_at is null or last_transaction_at < ? then ? else last_transaction_at end, updated_at = current_timestamp where id = ? and tenant_id = ?",
        Date.valueOf(row.getTransactionDate()), Date.valueOf(row.getTransactionDate()), bankAccountId, tenantId);
  }

  private Map<String, Object> getJob(Long jobId, Long tenantId) {
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, duplicate_suspected_rows, error_message, started_at, finished_at, preview_confirmed_at "
            + "from import_job where id = ? and tenant_id = ? and deleted_at is null",
        jobId,
        tenantId);
    if (items.isEmpty()) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入任务不存在");
    }
    Map<String, Object> result = new LinkedHashMap<>(items.get(0));
    result.put("error_details", listErrors(jobId, tenantId));
    return result;
  }

  public List<Map<String, Object>> listErrors(Long jobId) {
    AuthPrincipal principal = requireAuth();
    getJob(jobId, principal.getTenantId());
    return listErrors(jobId, principal.getTenantId());
  }

  public String downloadErrors(Long jobId) {
    AuthPrincipal principal = requireAuth();
    getJob(jobId, principal.getTenantId());
    StringBuilder csv = new StringBuilder("row_no,field,error_message,raw_json\n");
    for (Map<String, Object> row : listErrors(jobId, principal.getTenantId())) {
      csv.append(row.get("row_no")).append(',')
          .append(csvCell(row.get("field_name"))).append(',')
          .append(csvCell(row.get("error_message"))).append(',')
          .append(csvCell(row.get("raw_json"))).append('\n');
    }
    return csv.toString();
  }

  private List<Map<String, Object>> listErrors(Long jobId, Long tenantId) {
    return jdbcTemplate.queryForList(
        "select row_no, field_name, raw_json, error_message from import_row_error where import_job_id = ? and tenant_id = ? order by row_no",
        jobId, tenantId);
  }

  private String csvCell(Object value) {
    String text = value == null ? "" : value.toString();
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  private String appendError(String current, String next) {
    if (current == null) return next;
    return current.length() > 900 ? current : current + "；" + next;
  }

  private java.io.InputStream open(MultipartFile file) {
    try {
      return file.getInputStream();
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件读取失败");
    }
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }
    return principal;
  }
}
