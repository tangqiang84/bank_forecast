package com.bankforecast.contract;

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
public class ContractImportService {
  private final JdbcTemplate jdbcTemplate;
  private final CsvContractParser parser;
  private final AuditService auditService;
  private final int maxRows;
  private final long maxFileSize;
  private final BigDecimal maxAmount;

  public ContractImportService(JdbcTemplate jdbcTemplate, CsvContractParser parser, AuditService auditService,
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
  public Map<String, Object> importContracts(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "contracts.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    Long jobId = createJob(principal.getTenantId(), fileName);
    int success = 0;
    int failed = 0;
    int skipped = 0;
    String error = null;
    CsvParseResult<CsvContractRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertImportError(principal.getTenantId(), jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvContractRow row : parsed.getRows()) {
      try {
        validateAmount(row.getContractAmount(), row.getRowNo(), "contract_amount");
        if (row.getNodeName() != null && planExists(principal.getTenantId(), row)) {
          skipped++;
          continue;
        }
        Long projectId = upsertProject(principal.getTenantId(), row);
        Long contractId = findContract(principal.getTenantId(), row.getContractNo());
        if (contractId == null) contractId = insertContract(principal.getTenantId(), row, projectId);
        if (row.getNodeName() == null) {
          success++;
          continue;
        }
        validateAmount(row.getPlanAmount(), row.getRowNo(), "plan_amount");
        if (row.getPlanAmount().compareTo(row.getContractAmount()) > 0) {
          throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行应收计划金额不能超过合同金额");
        }
        validatePlanAmount(principal.getTenantId(), contractId, row);
        insertPlan(principal.getTenantId(), contractId, projectId, row);
        success++;
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
    auditService.record("IMPORT_CONTRACT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    return result;
  }

  private void validateAmount(BigDecimal amount, int rowNo, String field) {
    if (amount.scale() > 2 || amount.compareTo(maxAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 超出金额边界");
    }
  }

  private boolean planExists(Long tenantId, CsvContractRow row) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from contract_receivable_plan p join contract c on c.id = p.contract_id "
            + "where p.tenant_id = ? and c.contract_no = ? and p.node_name = ? and p.deleted_at is null and c.deleted_at is null",
        Integer.class, tenantId, row.getContractNo(), row.getNodeName());
    return count != null && count > 0;
  }

  private void validatePlanAmount(Long tenantId, Long contractId, CsvContractRow row) {
    java.math.BigDecimal total = jdbcTemplate.queryForObject(
        "select coalesce(sum(plan_amount), 0) from contract_receivable_plan where tenant_id = ? and contract_id = ? and deleted_at is null",
        java.math.BigDecimal.class, tenantId, contractId);
    BigDecimal contractAmount = jdbcTemplate.queryForObject(
        "select contract_amount from contract where id = ? and tenant_id = ?", BigDecimal.class, contractId, tenantId);
    if (contractAmount != null && total != null && total.add(row.getPlanAmount()).compareTo(contractAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行应收计划合计不能超过合同金额");
    }
  }

  public Map<String, Object> getJob(Long jobId) {
    AuthPrincipal principal = requireAuth();
    return getJob(jobId, principal.getTenantId());
  }

  private Long upsertProject(Long tenantId, CsvContractRow row) {
    if (row.getProjectNo() == null || row.getProjectNo().trim().isEmpty()) return null;
    List<Long> ids = jdbcTemplate.queryForList(
        "select id from project where tenant_id = ? and project_no = ? and deleted_at is null",
        Long.class, tenantId, row.getProjectNo().trim());
    if (!ids.isEmpty()) return ids.get(0);
    return insertProject(tenantId, row);
  }

  private Long insertProject(Long tenantId, CsvContractRow row) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setString(2, row.getProjectNo().trim());
      ps.setString(3, valueOrFallback(row.getProjectName(), row.getProjectNo()));
      ps.setString(4, row.getCustomerName());
      ps.setString(5, "active");
      return ps;
    }, keyHolder);
    return generatedId(keyHolder);
  }

  private Long findContract(Long tenantId, String contractNo) {
    List<Long> ids = jdbcTemplate.queryForList(
        "select id from contract where tenant_id = ? and contract_no = ? and deleted_at is null",
        Long.class, tenantId, contractNo);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private Long insertContract(Long tenantId, CsvContractRow row, Long projectId) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) "
              + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setString(2, row.getContractNo());
      ps.setString(3, row.getContractName());
      ps.setString(4, row.getCustomerName());
      if (projectId == null) ps.setObject(5, null); else ps.setLong(5, projectId);
      ps.setString(6, row.getProjectNo());
      ps.setString(7, row.getProjectName());
      ps.setBigDecimal(8, row.getContractAmount());
      ps.setString(9, "active");
      return ps;
    }, keyHolder);
    return generatedId(keyHolder);
  }

  private void insertPlan(Long tenantId, Long contractId, Long projectId, CsvContractRow row) {
    jdbcTemplate.update(
        "insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, status) values (?, ?, ?, ?, ?, ?, ?, ?)",
        tenantId, contractId, projectId, row.getNodeName(), row.getNodeType(), Date.valueOf(row.getDueDate()), row.getPlanAmount(), "unpaid");
  }

  private Long createJob(Long tenantId, String fileName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into import_job (tenant_id, job_type, source_type, file_name, status, started_at) values (?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setString(2, "contract"); ps.setString(3, "file"); ps.setString(4, fileName);
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
        "select id as job_id, job_type, source_type, file_name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message, started_at, finished_at from import_job where id = ? and tenant_id = ? and deleted_at is null",
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

  public String downloadErrors(Long jobId) {
    AuthPrincipal principal = requireAuth();
    getJob(jobId, principal.getTenantId());
    StringBuilder csv = new StringBuilder("row_no,field,error_message,raw_json\n");
    for (Map<String, Object> row : listErrors(jobId, principal.getTenantId())) {
      csv.append(row.get("row_no")).append(',').append(csvCell(row.get("field_name"))).append(',')
          .append(csvCell(row.get("error_message"))).append(',').append(csvCell(row.get("raw_json"))).append('\n');
    }
    return csv.toString();
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

  private String csvCell(Object value) {
    String text = value == null ? "" : value.toString();
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  private String appendError(String current, String next) {
    if (current == null) return next;
    return current.length() > 900 ? current : current + "；" + next;
  }

  private java.io.InputStream open(MultipartFile file) {
    try { return file.getInputStream(); }
    catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件读取失败"); }
  }

  private String valueOrFallback(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }

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
