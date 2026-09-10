package com.bankforecast.finance;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.importjob.CsvImportSupport;
import com.bankforecast.importjob.CsvParseResult;
import com.bankforecast.importjob.CsvRowError;
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
public class CsvFinanceRecordParser {
  private final ObjectMapper objectMapper;

  public CsvFinanceRecordParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

  public CsvParseResult<CsvFinanceRecordRow> parse(InputStream inputStream, int maxRows) {
    try {
      BufferedReader reader = new BufferedReader(new StringReader(CsvImportSupport.readText(inputStream)));
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.trim().isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
      char delimiter = CsvImportSupport.detectDelimiter(headerLine);
      List<String> headers = parseLine(headerLine, delimiter);
      Map<String, Integer> indexes = new HashMap<>();
      for (int i = 0; i < headers.size(); i++) indexes.put(canonicalHeader(headers.get(i)), i);
      for (String key : new String[] {"record_no", "record_type", "record_date", "amount"}) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }
      List<CsvFinanceRecordRow> rows = new ArrayList<>();
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
          String type = required(raw, "record_type", rowNo).toLowerCase();
          if (!type.equals("receipt") && !type.equals("payment") && !type.equals("voucher") && !type.equals("journal")) {
            throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 record_type 不支持");
          }
          BigDecimal amount = new BigDecimal(CsvImportSupport.normalizeAmount(required(raw, "amount", rowNo)));
          if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 必须大于 0");
          rows.add(new CsvFinanceRecordRow(rowNo, required(raw, "record_no", rowNo), type,
              parseDate(raw, "record_date", rowNo), optionalDate(raw, "posting_date", rowNo),
              optional(raw, "counterparty_name"), amount, optional(raw, "summary"), optional(raw, "source_system"),
              optional(raw, "contract_no"), optional(raw, "project_no"), optional(raw, "remark"), objectMapper.writeValueAsString(raw)));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
        } catch (NumberFormatException ex) {
          errors.add(new CsvRowError(rowNo, "amount", "第 " + rowNo + " 行 amount 格式错误", objectMapper.writeValueAsString(raw)));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "财务记录文件解析失败");
    }
  }

  private String fieldFromMessage(String message) {
    for (String field : new String[] {"record_no", "record_type", "record_date", "posting_date", "amount"}) {
      if (message != null && message.contains(field)) return field;
    }
    return "row";
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    String value = required(raw, key, rowNo);
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 日期格式错误");
  }

  private LocalDate optionalDate(Map<String, String> raw, String key, int rowNo) {
    return optional(raw, key) == null ? null : parseDate(raw, key, rowNo);
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
    List<String> values = new ArrayList<>(); StringBuilder current = new StringBuilder(); boolean quoted = false;
    for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c == '"') quoted = !quoted; else if (c == delimiter && !quoted) { values.add(current.toString()); current.setLength(0); } else current.append(c); }
    values.add(current.toString()); return values;
  }

  private String canonicalHeader(String header) {
    String normalized = CsvImportSupport.normalizeHeader(header);
    Map<String, String> aliases = new HashMap<>();
    aliases.put("recordno", "record_no"); aliases.put("凭证号", "record_no"); aliases.put("单据号", "record_no"); aliases.put("单据号/凭证号", "record_no");
    aliases.put("recordtype", "record_type"); aliases.put("记录类型", "record_type");
    aliases.put("recorddate", "record_date"); aliases.put("业务日期", "record_date");
    aliases.put("postingdate", "posting_date"); aliases.put("记账日期", "posting_date");
    aliases.put("counterpartyname", "counterparty_name"); aliases.put("对手方", "counterparty_name");
    aliases.put("amount", "amount"); aliases.put("金额", "amount");
    aliases.put("summary", "summary"); aliases.put("摘要", "summary");
    aliases.put("sourcesystem", "source_system"); aliases.put("来源系统", "source_system");
    aliases.put("contractno", "contract_no"); aliases.put("关联合同编号", "contract_no");
    aliases.put("projectno", "project_no"); aliases.put("关联项目编号", "project_no");
    aliases.put("remark", "remark"); aliases.put("备注", "remark");
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }
}
