package com.bankforecast.project;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.CsvImportSupport;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.CsvRowError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CsvProjectParser {

  static final List<String> ALLOWED_STATUSES = Arrays.asList("active", "paused", "completed", "cancelled");

  private final ObjectMapper objectMapper;

  public CsvProjectParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CsvParseResult<CsvProjectRow> parse(InputStream inputStream, int maxRows) {
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
      for (String key : new String[] {"project_no", "project_name"}) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }

      List<CsvProjectRow> rows = new ArrayList<>();
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
          String status = optional(raw, "project_status");
          if (status == null) status = "active";
          validateStatus(status, rowNo);
          LocalDate startDate = parseDate(raw, "start_date", rowNo);
          LocalDate deliveryDate = parseDate(raw, "delivery_date", rowNo);
          LocalDate acceptanceDate = parseDate(raw, "acceptance_date", rowNo);
          validateDateOrder(startDate, acceptanceDate, rowNo);
          rows.add(new CsvProjectRow(rowNo,
              required(raw, "project_no", rowNo),
              required(raw, "project_name", rowNo),
              optional(raw, "customer_name"),
              optional(raw, "project_manager"),
              status,
              startDate,
              deliveryDate,
              acceptanceDate,
              optional(raw, "remark"),
              objectMapper.writeValueAsString(raw)));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "项目文件解析失败");
    }
  }

  static void validateStatus(String status, int rowNo) {
    if (!ALLOWED_STATUSES.contains(status)) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR,
          "第 " + rowNo + " 行 project_status 不合法，仅支持 active/paused/completed/cancelled");
    }
  }

  static void validateDateOrder(LocalDate startDate, LocalDate acceptanceDate, int rowNo) {
    if (startDate != null && acceptanceDate != null && acceptanceDate.isBefore(startDate)) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行验收日期不能早于开始日期");
    }
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"project_no", "project_name", "project_status",
        "start_date", "delivery_date", "acceptance_date"}) {
      if (message.contains(field)) return field;
    }
    if (message.contains("日期")) return "start_date";
    return "row";
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    String value = optional(raw, key);
    if (value == null) return null;
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); }
      catch (DateTimeParseException ignored) {
        // 尝试下一种项目导出日期格式
      }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 日期格式错误");
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
    aliases.put("projectno", "project_no");
    aliases.put("projectname", "project_name");
    aliases.put("customername", "customer_name");
    aliases.put("projectmanager", "project_manager");
    aliases.put("projectstatus", "project_status");
    aliases.put("startdate", "start_date");
    aliases.put("deliverydate", "delivery_date");
    aliases.put("acceptancedate", "acceptance_date");
    aliases.put("项目编号", "project_no");
    aliases.put("项目名称", "project_name");
    aliases.put("客户名称", "customer_name");
    aliases.put("项目负责人", "project_manager");
    aliases.put("负责人", "project_manager");
    aliases.put("项目经理", "project_manager");
    aliases.put("项目状态", "project_status");
    aliases.put("开始日期", "start_date");
    aliases.put("交付日期", "delivery_date");
    aliases.put("验收日期", "acceptance_date");
    aliases.put("备注", "remark");
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }
}
