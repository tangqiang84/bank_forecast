package com.bankforecast.importjob;

import com.bankforecast.audit.AuditService;
import com.bankforecast.bank.BankAccountRepository;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
  private final AuditService auditService;
  private final int maxRows;
  private final long maxFileSize;
  private final int maxDateRangeDays;
  private final BigDecimal maxAmount;

  public ImportJobService(JdbcTemplate jdbcTemplate, CsvBankStatementParser parser, ExcelBankStatementParser excelParser,
      BankAccountRepository bankAccountRepository, AuditService auditService,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize,
      @Value("${bank-forecast.import.max-date-range-days}") int maxDateRangeDays,
      @Value("${bank-forecast.import.max-amount}") BigDecimal maxAmount) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.excelParser = excelParser;
    this.bankAccountRepository = bankAccountRepository;
    this.auditService = auditService;
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

    CsvParseResult<CsvBankStatementRow> parsed;
    List<Map<String, Object>> templates = new java.util.ArrayList<>();
    if (lowerFileName.endsWith(".xlsx")) {
      ExcelBankStatementParser.ExcelParseResult excel = excelParser.parse(open(file), maxRows);
      parsed = new CsvParseResult<>(excel.getRows(), excel.getErrors(), excel.getTotalRows());
      templates = excel.getTemplates();
    } else {
      parsed = parser.parse(open(file), maxRows);
    }
    for (CsvRowError rowError : parsed.getErrors()) {
      failedRows++;
      insertRawError(principal.getTenantId(), jobId, bankAccountId, rowError);
      insertImportError(principal.getTenantId(), jobId, rowError);
      errorMessage = appendError(errorMessage, rowError.getMessage());
    }
    for (CsvBankStatementRow row : parsed.getRows()) {
      try {
        validateDate(row);
        validateAmount(row.getAmount(), row.getRowNo(), "amount");
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
    finishJob(jobId, parsed.getTotalRows(), successRows, failedRows, skippedRows, status, errorMessage);
    auditService.record("IMPORT_BANK_STATEMENT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    result.put("file_format", lowerFileName.endsWith(".xlsx") ? "xlsx" : "csv");
    if (!templates.isEmpty()) result.put("recognized_templates", templates);
    return result;
  }

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
      String status, String errorMessage) {
    jdbcTemplate.update(
        "update import_job set status = ?, total_rows = ?, success_rows = ?, failed_rows = ?, skipped_rows = ?, error_message = ?, finished_at = ?, updated_at = ? where id = ?",
        status,
        totalRows,
        successRows,
        failedRows,
        skippedRows,
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
        "insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, booking_date, direction, amount, balance_after, counterparty_name, summary, match_status) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
        "unmatched");
  }

  private Map<String, Object> getJob(Long jobId, Long tenantId) {
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message, started_at, finished_at "
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
