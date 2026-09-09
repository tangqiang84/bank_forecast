package com.bankforecast.importjob;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
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
public class CsvBankStatementParser {

  private final ObjectMapper objectMapper;

  public CsvBankStatementParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CsvParseResult<CsvBankStatementRow> parse(InputStream inputStream, int maxRows) {
    try {
      BufferedReader reader = new BufferedReader(new StringReader(CsvImportSupport.readText(inputStream)));
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
      }

      List<String> headers = parseLine(headerLine);
      for (int i = 0; i < headers.size(); i++) headers.set(i, canonicalHeader(headers.get(i)));
      Map<String, Integer> headerIndex = headerIndex(headers);
      requireHeaders(headerIndex);

      List<CsvBankStatementRow> rows = new ArrayList<>();
      List<CsvRowError> errors = new ArrayList<>();
      String line;
      int rowNo = 1;
      int dataRows = 0;
      while ((line = reader.readLine()) != null) {
        rowNo++;
        if (line.trim().isEmpty()) {
          continue;
        }
        if (dataRows >= maxRows) {
          throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
        }
        dataRows++;
        List<String> values = parseLine(line);
        String rawJson = rawJson(headers, values);
        try {
          rows.add(toRow(rowNo, headers, headerIndex, values));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), rawJson));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件解析失败");
    }
  }

  private String rawJson(List<String> headers, List<String> values) throws Exception {
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) raw.put(headers.get(i), value(values, i));
    return objectMapper.writeValueAsString(raw);
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"transaction_no", "transaction_date", "direction", "amount", "balance_after"}) {
      if (message.contains(field)) return field;
    }
    if (message.contains("日期")) return "transaction_date";
    if (message.contains("金额")) return "amount";
    return "row";
  }

  private CsvBankStatementRow toRow(int rowNo, List<String> headers, Map<String, Integer> headerIndex,
      List<String> values) throws Exception {
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      raw.put(headers.get(i), value(values, i));
    }

    String transactionNo = required(raw, "transaction_no", rowNo);
    LocalDate transactionDate = parseDate(raw, "transaction_date", rowNo);
    String direction = normalizeDirection(required(raw, "direction", rowNo));
    BigDecimal amount = decimal(raw, "amount", rowNo);
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
    }

    String balanceText = raw.get("balance_after");
    BigDecimal balanceAfter = balanceText == null || balanceText.trim().isEmpty()
        ? null : decimalValue(balanceText, "balance_after", rowNo);
    return new CsvBankStatementRow(
        rowNo,
        transactionNo,
        transactionDate,
        direction,
        amount,
        balanceAfter,
        raw.get("counterparty_name"),
        raw.get("summary"),
        objectMapper.writeValueAsString(raw));
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    try {
      String value = required(raw, key, rowNo);
      for (DateTimeFormatter formatter : new DateTimeFormatter[] {
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("yyyy/MM/dd"),
          DateTimeFormatter.ofPattern("yyyyMMdd")}) {
        try {
          return LocalDate.parse(value, formatter);
        } catch (DateTimeParseException ignored) {
          // 尝试下一种银行导出日期格式
        }
      }
      throw new DateTimeParseException("invalid date", value, 0);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误");
    }
  }

  private BigDecimal decimal(Map<String, String> raw, String key, int rowNo) {
    return decimalValue(required(raw, key, rowNo), key, rowNo);
  }

  private BigDecimal decimalValue(String value, String key, int rowNo) {
    try {
      return new BigDecimal(CsvImportSupport.normalizeAmount(value));
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 格式错误");
    }
  }

  private Map<String, Integer> headerIndex(List<String> headers) {
    Map<String, Integer> map = new HashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      map.put(canonicalHeader(headers.get(i)), i);
    }
    return map;
  }

  private void requireHeaders(Map<String, Integer> headerIndex) {
    String[] required = {"transaction_no", "transaction_date", "direction", "amount"};
    for (String item : required) {
      if (!headerIndex.containsKey(item)) {
        throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + item);
      }
    }
  }

  private String required(Map<String, String> raw, String key, int rowNo) {
    String value = raw.get(key);
    if (value == null || value.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空");
    }
    return value.trim();
  }

  private String normalizeDirection(String direction) {
    direction = direction.trim().toLowerCase();
    if ("income".equals(direction) || "收入".equals(direction)) {
      return "income";
    }
    if ("expense".equals(direction) || "支出".equals(direction) || "out".equals(direction)
        || "debit".equals(direction) || "借方".equals(direction)) {
      return "expense";
    }
    if ("in".equals(direction) || "credit".equals(direction) || "贷方".equals(direction)) {
      return "income";
    }
    if ("transfer".equals(direction) || "内部转账".equals(direction)) {
      return "transfer";
    }
    if ("refund".equals(direction) || "退款".equals(direction)) {
      return "refund";
    }
    if ("reversal".equals(direction) || "冲正".equals(direction)) {
      return "reversal";
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "交易方向不合法");
  }

  private String value(List<String> values, int index) {
    return index < values.size() ? values.get(index).trim() : "";
  }

  private String canonicalHeader(String header) {
    String normalized = CsvImportSupport.normalizeHeader(header);
    Map<String, String> aliases = new HashMap<>();
    aliases.put("transactionno", "transaction_no");
    aliases.put("transactiondate", "transaction_date");
    aliases.put("counterpartyname", "counterparty_name");
    aliases.put("balanceafter", "balance_after");
    aliases.put("交易流水号", "transaction_no");
    aliases.put("流水号", "transaction_no");
    aliases.put("交易日期", "transaction_date");
    aliases.put("日期", "transaction_date");
    aliases.put("收支方向", "direction");
    aliases.put("借贷标志", "direction");
    aliases.put("金额", "amount");
    aliases.put("交易金额", "amount");
    aliases.put("余额", "balance_after");
    aliases.put("对方户名", "counterparty_name");
    aliases.put("摘要", "summary");
    aliases.put("备注", "summary");
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }

  private List<String> parseLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        inQuote = !inQuote;
      } else if (c == ',' && !inQuote) {
        values.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    values.add(current.toString());
    return values;
  }
}
