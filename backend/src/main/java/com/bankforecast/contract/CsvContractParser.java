package com.bankforecast.contract;

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
public class CsvContractParser {
  private final ObjectMapper objectMapper;

  public CsvContractParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<CsvContractRow> parse(InputStream inputStream, int maxRows) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
      }
      List<String> headers = parseLine(headerLine);
      Map<String, Integer> indexes = new HashMap<>();
      for (int i = 0; i < headers.size(); i++) indexes.put(headers.get(i).trim(), i);
      String[] required = {"contract_no", "contract_name", "customer_name", "contract_amount",
          "node_name", "node_type", "due_date", "plan_amount"};
      for (String key : required) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }

      List<CsvContractRow> rows = new ArrayList<>();
      String line;
      int rowNo = 1;
      while ((line = reader.readLine()) != null) {
        rowNo++;
        if (line.trim().isEmpty()) continue;
        if (rows.size() >= maxRows) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
        Map<String, String> raw = new LinkedHashMap<>();
        List<String> values = parseLine(line);
        for (int i = 0; i < headers.size(); i++) raw.put(headers.get(i).trim(), value(values, i));
        rows.add(new CsvContractRow(rowNo,
            required(raw, "contract_no", rowNo),
            required(raw, "contract_name", rowNo),
            required(raw, "customer_name", rowNo),
            raw.get("project_no"), raw.get("project_name"),
            positive(raw, "contract_amount", rowNo),
            required(raw, "node_name", rowNo),
            required(raw, "node_type", rowNo),
            parseDate(raw, "due_date", rowNo),
            positive(raw, "plan_amount", rowNo), raw.get("owner_name"),
            objectMapper.writeValueAsString(raw)));
      }
      return rows;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "合同文件解析失败");
    }
  }

  private BigDecimal positive(Map<String, String> raw, String key, int rowNo) {
    try {
      BigDecimal amount = new BigDecimal(required(raw, key, rowNo));
      if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
      return amount;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额格式错误");
    }
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    try { return LocalDate.parse(required(raw, key, rowNo)); }
    catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误"); }
  }

  private String required(Map<String, String> raw, String key, int rowNo) {
    String value = raw.get(key);
    if (value == null || value.trim().isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空");
    return value.trim();
  }

  private String value(List<String> values, int index) { return index < values.size() ? values.get(index).trim() : ""; }

  private List<String> parseLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (c == ',' && !quoted) { values.add(current.toString()); current.setLength(0); }
      else current.append(c);
    }
    values.add(current.toString());
    return values;
  }
}
