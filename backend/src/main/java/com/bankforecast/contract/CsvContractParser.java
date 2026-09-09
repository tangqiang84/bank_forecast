package com.bankforecast.contract;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.CsvRowError;
import com.bankforecast.importjob.CsvImportSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CsvContractParser {
  private final ObjectMapper objectMapper;

  public CsvContractParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CsvParseResult<CsvContractRow> parse(InputStream inputStream, int maxRows) {
    try {
      BufferedReader reader = new BufferedReader(new StringReader(CsvImportSupport.readText(inputStream)));
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
      }
      char delimiter = CsvImportSupport.detectDelimiter(headerLine);
      List<String> headers = parseLine(headerLine, delimiter);
      Map<String, Integer> indexes = new HashMap<>();
      for (int i = 0; i < headers.size(); i++) indexes.put(canonicalHeader(headers.get(i)), i);
      String[] required = {"contract_no", "customer_name"};
      for (String key : required) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }
      boolean hasReceivablePlan = indexes.containsKey("node_name") && indexes.containsKey("due_date")
          && indexes.containsKey("plan_amount");

      List<CsvContractRow> rows = new ArrayList<>();
      List<CsvRowError> errors = new ArrayList<>();
      String line;
      int rowNo = 1;
      int dataRows = 0;
      while ((line = reader.readLine()) != null) {
        rowNo++;
        if (line.trim().isEmpty()) continue;
        if (dataRows >= maxRows) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
        dataRows++;
        Map<String, String> raw = new LinkedHashMap<>();
        List<String> values = parseLine(line, delimiter);
        for (int i = 0; i < headers.size(); i++) raw.put(canonicalHeader(headers.get(i)), value(values, i));
        try {
          String contractName = optional(raw, "contract_name");
          if (contractName == null) contractName = optional(raw, "project_name");
          if (contractName == null) contractName = required(raw, "contract_no", rowNo);
          rows.add(new CsvContractRow(rowNo,
              required(raw, "contract_no", rowNo),
              contractName,
              required(raw, "customer_name", rowNo),
              raw.get("project_no"), raw.get("project_name"),
              optionalPositive(raw, "contract_amount", rowNo),
              hasReceivablePlan ? required(raw, "node_name", rowNo) : null,
              hasReceivablePlan ? optionalOrDefault(raw, "node_type", "receivable") : null,
              hasReceivablePlan ? parseDate(raw, "due_date", rowNo) : null,
              hasReceivablePlan ? positive(raw, "plan_amount", rowNo) : null,
              raw.get("owner_name"),
              objectMapper.writeValueAsString(raw)));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "合同文件解析失败");
    }
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"contract_no", "contract_name", "customer_name", "contract_amount",
        "node_name", "node_type", "due_date", "plan_amount"}) {
      if (message.contains(field)) return field;
    }
    if (message.contains("日期")) return "due_date";
    if (message.contains("金额")) return "plan_amount";
    return "row";
  }

  private BigDecimal positive(Map<String, String> raw, String key, int rowNo) {
    try {
      BigDecimal amount = new BigDecimal(CsvImportSupport.normalizeAmount(required(raw, key, rowNo)));
      if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
      return amount;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额格式错误");
    }
  }

  private BigDecimal optionalPositive(Map<String, String> raw, String key, int rowNo) {
    return optional(raw, key) == null ? null : positive(raw, key, rowNo);
  }

  private String optionalOrDefault(Map<String, String> raw, String key, String defaultValue) {
    String value = optional(raw, key);
    return value == null ? defaultValue : value;
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    String value = required(raw, key, rowNo);
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

  private String required(Map<String, String> raw, String key, int rowNo) {
    String value = raw.get(key);
    if (value == null || value.trim().isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空");
    return value.trim();
  }

  private String optional(Map<String, String> raw, String key) {
    String value = raw.get(key);
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private String value(List<String> values, int index) { return index < values.size() ? values.get(index).trim() : ""; }

  private List<String> parseLine(String line, char delimiter) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (c == delimiter && !quoted) { values.add(current.toString()); current.setLength(0); }
      else current.append(c);
    }
    values.add(current.toString());
    return values;
  }

  private String canonicalHeader(String header) {
    String normalized = CsvImportSupport.normalizeHeader(header);
    Map<String, String> aliases = new HashMap<>();
    aliases.put("contractno", "contract_no");
    aliases.put("contractname", "contract_name");
    aliases.put("customername", "customer_name");
    aliases.put("contractamount", "contract_amount");
    aliases.put("nodename", "node_name");
    aliases.put("nodetype", "node_type");
    aliases.put("duedate", "due_date");
    aliases.put("planamount", "plan_amount");
    aliases.put("projectno", "project_no");
    aliases.put("projectname", "project_name");
    aliases.put("ownername", "owner_name");
    aliases.put("合同编号", "contract_no");
    aliases.put("合同名称", "contract_name");
    aliases.put("合同名", "contract_name");
    aliases.put("客户名称", "customer_name");
    aliases.put("合同金额", "contract_amount");
    aliases.put("节点名称", "node_name");
    aliases.put("节点类型", "node_type");
    aliases.put("应收日期", "due_date");
    aliases.put("到期日期", "due_date");
    aliases.put("计划金额", "plan_amount");
    aliases.put("应收金额", "plan_amount");
    aliases.put("项目编号", "project_no");
    aliases.put("项目名称", "project_name");
    aliases.put("负责人", "owner_name");
    if (normalized.contains("合同") && normalized.contains("名称")) return "contract_name";
    if (normalized.contains("contract") && normalized.contains("name")) return "contract_name";
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }
}
