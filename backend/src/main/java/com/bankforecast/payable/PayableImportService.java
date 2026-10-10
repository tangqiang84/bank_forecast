package com.bankforecast.payable;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
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

/** 应付计划 CSV 导入：预览、失败行修正重试、确认全流程，对齐统一导入任务模型。 */
@Service
public class PayableImportService {
  private final JdbcTemplate jdbcTemplate;
  private final CsvPayablePlanParser parser;
  private final AuditService auditService;
  private final PermissionRepository permissionRepository;
  private final ObjectMapper objectMapper;
  private final int maxRows;
  private final long maxFileSize;

  public PayableImportService(JdbcTemplate jdbcTemplate, CsvPayablePlanParser parser,
      AuditService auditService, PermissionRepository permissionRepository, ObjectMapper objectMapper,
      @Value("${bank-forecast.import.max-rows}") int maxRows,
      @Value("${bank-forecast.import.max-file-size-bytes}") long maxFileSize) {
    this.jdbcTemplate = jdbcTemplate;
    this.parser = parser;
    this.auditService = auditService;
    this.permissionRepository = permissionRepository;
    this.objectMapper = objectMapper;
    this.maxRows = maxRows;
    this.maxFileSize = maxFileSize;
  }

  @Transactional
  public Map<String, Object> importPayables(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    String fileName = validateFile(file);
    Long jobId = createJob(principal.getTenantId(), fileName);
    int success = 0;
    int failed = 0;
    int skipped = 0;
    String error = null;
    CsvParseResult<CsvPayablePlanRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertImportError(principal.getTenantId(), jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvPayablePlanRow row : parsed.getRows()) {
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
    auditService.record("IMPORT_PAYABLE", "import_job", String.valueOf(jobId), "file=" + fileName);
    Map<String, Object> result = getJob(jobId, principal.getTenantId());
    result.put("error_details", listErrors(jobId, principal.getTenantId()));
    return result;
  }

  private String importRow(Long tenantId, Long jobId, CsvPayablePlanRow row) {
    Long contractId = resolveContract(tenantId, row.getContractNo(), row.getRowNo());
    if (planExists(tenantId, contractId, row)) {
      return "skipped";
    }
    insertPlan(tenantId, jobId, contractId, row);
    return "success";
  }

  @Transactional
  public Map<String, Object> previewPayables(MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    String fileName = validateFile(file);
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId, fileName);
    int valid = 0;
    int failed = 0;
    String error = null;
    CsvParseResult<CsvPayablePlanRow> parsed = parser.parse(open(file), maxRows);
    for (CsvRowError rowError : parsed.getErrors()) {
      failed++;
      insertPreviewItem(tenantId, jobId, rowError.getRowNo(), "failed", rowError.getRawJson(), rowError.getMessage());
      insertImportError(tenantId, jobId, rowError);
      error = appendError(error, rowError.getMessage());
    }
    for (CsvPayablePlanRow row : parsed.getRows()) {
      try {
        resolveContract(tenantId, row.getContractNo(), row.getRowNo());
        insertPreviewItem(tenantId, jobId, row.getRowNo(), "valid", writePayload(toPayload(row)), null);
        valid++;
      } catch (BusinessException ex) {
        failed++;
        insertPreviewItem(tenantId, jobId, row.getRowNo(), "failed", row.getRawJson(), ex.getMessage());
        insertImportError(tenantId, jobId, new CsvRowError(row.getRowNo(), "row", ex.getMessage(), row.getRawJson()));
        error = appendError(error, ex.getMessage());
      }
    }
    String status = valid == 0 ? "preview_failed" : "preview_pending";
    finishJob(jobId, parsed.getTotalRows(), valid, failed, 0, status, error);
    auditService.record("PREVIEW_PAYABLE", "import_job", String.valueOf(jobId), "file=" + fileName);
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
        CsvPayablePlanRow row = rowFromPayload(rowNo, readPayload(item.get("payload_json")));
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
    auditService.record("CONFIRM_PAYABLE", "import_job", String.valueOf(jobId), "success=" + success + ",skipped=" + skipped);
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
        CsvPayablePlanRow row = rowFromPayload(rowNo, merged);
        resolveContract(tenantId, row.getContractNo(), rowNo);
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
    auditService.record("RETRY_PAYABLE_ERRORS", "import_job", String.valueOf(jobId), "retried=" + retried + ",succeeded=" + succeeded);
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

  private Map<String, Object> toPayload(CsvPayablePlanRow row) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contract_no", row.getContractNo());
    payload.put("supplier_name", row.getSupplierName());
    payload.put("node_name", row.getNodeName());
    payload.put("due_date", row.getDueDate().toString());
    payload.put("plan_amount", row.getPlanAmount().toPlainString());
    payload.put("paid_amount", row.getPaidAmount().toPlainString());
    payload.put("status", row.getStatus());
    payload.put("remark", row.getRemark());
    return payload;
  }

  private CsvPayablePlanRow rowFromPayload(int rowNo, Map<String, Object> payload) {
    BigDecimal planAmount = decimal(requiredText(payload.get("plan_amount"), rowNo, "plan_amount"), rowNo, "plan_amount");
    BigDecimal paidAmount = textOrNull(payload.get("paid_amount")) == null
        ? BigDecimal.ZERO.setScale(2) : decimal(String.valueOf(payload.get("paid_amount")), rowNo, "paid_amount");
    if (planAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 plan_amount 必须大于 0");
    }
    if (paidAmount.compareTo(BigDecimal.ZERO) < 0 || paidAmount.compareTo(planAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 paid_amount 必须介于 0 和 plan_amount 之间");
    }
    String status = textOrNull(payload.get("status"));
    if (status == null) {
      status = paidAmount.compareTo(BigDecimal.ZERO) == 0 ? "unpaid"
          : paidAmount.compareTo(planAmount) >= 0 ? "paid" : "partial";
    }
    status = CsvPayablePlanParser.normalizeStatus(status, rowNo);
    LocalDate dueDate;
    try {
      dueDate = LocalDate.parse(requiredText(payload.get("due_date"), rowNo, "due_date"));
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 due_date 日期格式错误");
    }
    return new CsvPayablePlanRow(rowNo,
        textOrNull(payload.get("contract_no")),
        requiredText(payload.get("supplier_name"), rowNo, "supplier_name"),
        requiredText(payload.get("node_name"), rowNo, "node_name"),
        dueDate, planAmount, paidAmount, status,
        textOrNull(payload.get("remark")),
        writePayload(payload));
  }

  private Long resolveContract(Long tenantId, String contractNo, int rowNo) {
    if (contractNo == null) return null;
    List<Long> ids = jdbcTemplate.queryForList(
        "select id from contract where tenant_id = ? and contract_no = ? and deleted_at is null",
        Long.class, tenantId, contractNo);
    if (ids.isEmpty()) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行合同不存在：" + contractNo);
    }
    return ids.get(0);
  }

  private boolean planExists(Long tenantId, Long contractId, CsvPayablePlanRow row) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from contract_payable_plan where tenant_id = ? and coalesce(contract_id, 0) = ? and supplier_name = ? and node_name = ? and due_date = ? and plan_amount = ? and deleted_at is null",
        Integer.class, tenantId, contractId == null ? 0L : contractId,
        row.getSupplierName(), row.getNodeName(), Date.valueOf(row.getDueDate()), row.getPlanAmount());
    return count != null && count > 0;
  }

  private Long insertPlan(Long tenantId, Long jobId, Long contractId, CsvPayablePlanRow row) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into contract_payable_plan (tenant_id, contract_id, supplier_name, node_name, due_date, plan_amount, paid_amount, status, import_job_id, remark) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      if (contractId == null) ps.setObject(2, null); else ps.setLong(2, contractId);
      ps.setString(3, row.getSupplierName());
      ps.setString(4, row.getNodeName());
      ps.setDate(5, Date.valueOf(row.getDueDate()));
      ps.setBigDecimal(6, row.getPlanAmount());
      ps.setBigDecimal(7, row.getPaidAmount());
      ps.setString(8, row.getStatus());
      ps.setLong(9, jobId);
      ps.setString(10, row.getRemark());
      return ps;
    }, keyHolder);
    return generatedId(keyHolder);
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

  private String validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > maxFileSize) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件大小超过上限 " + maxFileSize + " 字节");
    String fileName = file.getOriginalFilename() == null ? "payables.csv" : file.getOriginalFilename();
    if (!fileName.toLowerCase().endsWith(".csv")) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "当前接口先支持 CSV 文件导入");
    return fileName;
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

  private BigDecimal decimal(String value, int rowNo, String field) {
    try {
      return new BigDecimal(value.replace(",", "").trim());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 格式错误");
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
      ps.setLong(1, tenantId); ps.setString(2, "payable"); ps.setString(3, "file"); ps.setString(4, fileName);
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
