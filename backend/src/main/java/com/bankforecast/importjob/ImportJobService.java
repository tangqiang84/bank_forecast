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
  private final BankAccountRepository bankAccountRepository;
  private final AuditService auditService;
  private final int maxRows;

  public ImportJobService(JdbcTemplate jdbcTemplate, CsvBankStatementParser parser,
      BankAccountRepository bankAccountRepository, AuditService auditService,
      @Value("${bank-forecast.import.max-rows}") int maxRows) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.bankAccountRepository = bankAccountRepository;
    this.auditService = auditService;
    this.maxRows = maxRows;
  }

  @Transactional
  public Map<String, Object> importBankStatements(Long bankAccountId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    }
    String fileName = file.getOriginalFilename() == null ? "bank_statement.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) {
      throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    }
    if (!bankAccountRepository.existsByTenant(principal.getTenantId(), bankAccountId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
    }

    Long jobId = createJob(principal.getTenantId(), fileName);
    int successRows = 0;
    int failedRows = 0;
    String errorMessage = null;

    List<CsvBankStatementRow> rows = parser.parse(open(file), maxRows);
    for (CsvBankStatementRow row : rows) {
      try {
        insertTransaction(principal.getTenantId(), jobId, bankAccountId, row);
        insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "success", null);
        successRows++;
      } catch (DuplicateKeyException ex) {
        failedRows++;
        errorMessage = "存在重复流水号";
        insertRaw(principal.getTenantId(), jobId, bankAccountId, row, "failed", "重复流水号");
      }
    }

    String status = failedRows == 0 ? "success" : (successRows == 0 ? "failed" : "partial_success");
    finishJob(jobId, rows.size(), successRows, failedRows, status, errorMessage);
    auditService.record("IMPORT_BANK_STATEMENT", "import_job", String.valueOf(jobId), "file=" + fileName);
    return getJob(jobId, principal.getTenantId());
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

  private void finishJob(Long jobId, int totalRows, int successRows, int failedRows,
      String status, String errorMessage) {
    jdbcTemplate.update(
        "update import_job set status = ?, total_rows = ?, success_rows = ?, failed_rows = ?, error_message = ?, finished_at = ?, updated_at = ? where id = ?",
        status,
        totalRows,
        successRows,
        failedRows,
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
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, error_message, started_at, finished_at "
            + "from import_job where id = ? and tenant_id = ? and deleted_at is null",
        jobId,
        tenantId);
    if (items.isEmpty()) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入任务不存在");
    }
    return new LinkedHashMap<>(items.get(0));
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
