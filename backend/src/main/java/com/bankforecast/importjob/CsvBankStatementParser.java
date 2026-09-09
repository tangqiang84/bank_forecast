package com.bankforecast.importjob;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

  public List<CsvBankStatementRow> parse(InputStream inputStream, int maxRows) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
      }

      List<String> headers = parseLine(headerLine);
      Map<String, Integer> headerIndex = headerIndex(headers);
      requireHeaders(headerIndex);

      List<CsvBankStatementRow> rows = new ArrayList<>();
      String line;
      int rowNo = 1;
      while ((line = reader.readLine()) != null) {
        rowNo++;
        if (line.trim().isEmpty()) {
          continue;
        }
        if (rows.size() >= maxRows) {
          throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
        }
        rows.add(toRow(rowNo, headers, headerIndex, parseLine(line)));
      }
      return rows;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "文件解析失败");
    }
  }

  private CsvBankStatementRow toRow(int rowNo, List<String> headers, Map<String, Integer> headerIndex,
      List<String> values) throws Exception {
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      raw.put(headers.get(i), value(values, i));
    }

    String transactionNo = required(raw, "transaction_no", rowNo);
    LocalDate transactionDate = LocalDate.parse(required(raw, "transaction_date", rowNo));
    String direction = normalizeDirection(required(raw, "direction", rowNo));
    BigDecimal amount = new BigDecimal(required(raw, "amount", rowNo));
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
    }

    String balanceText = raw.get("balance_after");
    BigDecimal balanceAfter = balanceText == null || balanceText.trim().isEmpty()
        ? null : new BigDecimal(balanceText.trim());
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

  private Map<String, Integer> headerIndex(List<String> headers) {
    Map<String, Integer> map = new HashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      map.put(headers.get(i).trim(), i);
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
    if ("income".equals(direction) || "收入".equals(direction)) {
      return "income";
    }
    if ("expense".equals(direction) || "支出".equals(direction)) {
      return "expense";
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
