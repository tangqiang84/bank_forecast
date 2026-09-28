package com.bankforecast.contract;

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
public class ContractImportService {
  private final JdbcTemplate jdbcTemplate;
  private final CsvContractParser parser;
  private final AuditService auditService;
  private final PermissionRepository permissionRepository;
  private final ObjectMapper objectMapper;
  private final int maxRows;
  private final long maxFileSize;
  private final BigDecimal maxAmount;

  public ContractImportService(JdbcTemplate jdbcTemplate, CsvContractParser parser, AuditService auditService,
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
        if ("skipped".equals(importRow(principal.getTenantId(), row))) {
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
    auditService.record("IMPORT_CONTRACT", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    return result;
  }

  private String importRow(Long tenantId, CsvContractRow row) {
    if (row.getNodeName() != null && planExists(tenantId, row)) {
      return "skipped";
    }
    Long contractId = findContract(tenantId, row.getContractNo());
    if (contractId == null && row.getContractAmount() == null) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR,
          "第 " + row.getRowNo() + " 行缺少 contract_amount，且合同 " + row.getContractNo() + " 不存在");
    }
    if (row.getContractAmount() != null) {
      validateAmount(row.getContractAmount(), row.getRowNo(), "contract_amount");
    }
    Long projectId = upsertProject(tenantId, row);
    if (contractId == null) contractId = insertContract(tenantId, row, projectId);
    if (row.getNodeName() == null) {
      return "success";
    }
    validateAmount(row.getPlanAmount(), row.getRowNo(), "plan_amount");
    BigDecimal contractAmount = row.getContractAmount() == null
        ? jdbcTemplate.queryForObject("select contract_amount from contract where id = ? and tenant_id = ?", BigDecimal.class, contractId, tenantId)
        : row.getContractAmount();
    if (contractAmount != null && row.getPlanAmount().compareTo(contractAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行应收计划金额不能超过合同金额");
    }
    validatePlanAmount(tenantId, contractId, row);
    insertPlan(tenantId, contractId, projectId, row);
    return "success";
  }

  @Transactional
  public Map<String, Object> previewContracts(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "contracts.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId, fileName);
    int valid = 0;
    int failed = 0;
    String error = null;
    CsvParseResult<CsvContractRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertPreviewItem(tenantId, jobId, rowError.getRowNo(), "failed", rowError.getRawJson(), rowError.getMessage());
      insertImportError(tenantId, jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvContractRow row : parsed.getRows()) {
      try {
        validatePreviewRow(row);
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
    auditService.record("PREVIEW_CONTRACT", "import_job", String.valueOf(jobId), "file=" + fileName);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> confirmPreview(Long jobId) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "contract:import");
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
        CsvContractRow row = rowFromPayload(rowNo, readPayload(item.get("payload_json")));
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
    auditService.record("CONFIRM_CONTRACT", "import_job", String.valueOf(jobId), "success=" + success + ",skipped=" + skipped);
    return getPreview(jobId, tenantId);
  }

  @Transactional
  public Map<String, Object> retryPreviewErrors(Long jobId, Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    requirePermission(principal, "contract:import");
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
        CsvContractRow row = rowFromPayload(rowNo, merged);
        validatePreviewRow(row);
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
    auditService.record("RETRY_CONTRACT_ERRORS", "import_job", String.valueOf(jobId), "retried=" + retried + ",succeeded=" + succeeded);
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

  private void validatePreviewRow(CsvContractRow row) {
    if (row.getContractAmount() != null) {
      validateAmount(row.getContractAmount(), row.getRowNo(), "contract_amount");
    }
    if (row.getNodeName() != null) {
      validateAmount(row.getPlanAmount(), row.getRowNo(), "plan_amount");
      if (row.getContractAmount() != null && row.getPlanAmount().compareTo(row.getContractAmount()) > 0) {
        throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + row.getRowNo() + " 行应收计划金额不能超过合同金额");
      }
    }
  }

  private Map<String, Object> toPayload(CsvContractRow row) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contract_no", row.getContractNo());
    payload.put("contract_name", row.getContractName());
    payload.put("customer_name", row.getCustomerName());
    payload.put("project_no", row.getProjectNo());
    payload.put("project_name", row.getProjectName());
    payload.put("contract_amount", row.getContractAmount() == null ? null : row.getContractAmount().toPlainString());
    payload.put("node_name", row.getNodeName());
    payload.put("node_type", row.getNodeType());
    payload.put("due_date", row.getDueDate() == null ? null : row.getDueDate().toString());
    payload.put("plan_amount", row.getPlanAmount() == null ? null : row.getPlanAmount().toPlainString());
    payload.put("owner_name", row.getOwnerName());
    return payload;
  }

  private CsvContractRow rowFromPayload(int rowNo, Map<String, Object> payload) {
    String contractNo = requiredText(payload.get("contract_no"), rowNo, "contract_no");
    String customerName = requiredText(payload.get("customer_name"), rowNo, "customer_name");
    String contractName = textOrNull(payload.get("contract_name"));
    if (contractName == null) contractName = textOrNull(payload.get("project_name"));
    if (contractName == null) contractName = contractNo;
    BigDecimal contractAmount = positiveDecimalOrNull(payload.get("contract_amount"), rowNo, "contract_amount");
    String nodeName = textOrNull(payload.get("node_name"));
    String nodeType = textOrNull(payload.get("node_type"));
    LocalDate dueDate = null;
    BigDecimal planAmount = null;
    if (nodeName != null) {
      if (nodeType == null) nodeType = "receivable";
      dueDate = parseDate(requiredText(payload.get("due_date"), rowNo, "due_date"), rowNo);
      planAmount = positiveDecimal(payload.get("plan_amount"), rowNo, "plan_amount");
    }
    return new CsvContractRow(rowNo, contractNo, contractName, customerName,
        textOrNull(payload.get("project_no")), textOrNull(payload.get("project_name")), contractAmount,
        nodeName, nodeType, dueDate, planAmount, textOrNull(payload.get("owner_name")), writePayload(payload));
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

  private BigDecimal positiveDecimal(Object value, int rowNo, String field) {
    String text = requiredText(value, rowNo, field);
    return positiveDecimal(text, rowNo, field);
  }

  private BigDecimal positiveDecimalOrNull(Object value, int rowNo, String field) {
    String text = textOrNull(value);
    return text == null ? null : positiveDecimal(text, rowNo, field);
  }

  private BigDecimal positiveDecimal(String text, int rowNo, String field) {
    try {
      BigDecimal amount = new BigDecimal(CsvImportSupport.normalizeAmount(text));
      if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
      return amount;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额格式错误");
    }
  }

  private LocalDate parseDate(String value, int rowNo) {
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); }
      catch (DateTimeParseException ignored) {
        // 尝试下一种合同导出日期格式
      }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误");
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
