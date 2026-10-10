package com.bankforecast.payable;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CsvPayablePlanParser {

  static final List<String> ALLOWED_STATUSES = Arrays.asList("unpaid", "partial", "paid");

  private final ObjectMapper objectMapper;

  public CsvPayablePlanParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CsvParseResult<CsvPayablePlanRow> parse(InputStream inputStream, int maxRows) {
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
      for (String key : new String[] {"supplier_name", "node_name", "due_date", "plan_amount"}) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }

      List<CsvPayablePlanRow> rows = new ArrayList<>();
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
          rows.add(toRow(rowNo, raw));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "应付计划文件解析失败");
    }
  }

  private CsvPayablePlanRow toRow(int rowNo, Map<String, String> raw) throws Exception {
    BigDecimal planAmount = decimal(raw.get("plan_amount"), "plan_amount", rowNo);
    if (planAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 plan_amount 必须大于 0");
    }
    BigDecimal paidAmount = optional(raw, "paid_amount") == null
        ? BigDecimal.ZERO.setScale(2) : decimal(raw.get("paid_amount"), "paid_amount", rowNo);
    if (paidAmount.compareTo(BigDecimal.ZERO) < 0 || paidAmount.compareTo(planAmount) > 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 paid_amount 必须介于 0 和 plan_amount 之间");
    }
    String status = optional(raw, "status");
    if (status == null) {
      status = paidAmount.compareTo(BigDecimal.ZERO) == 0 ? "unpaid"
          : paidAmount.compareTo(planAmount) >= 0 ? "paid" : "partial";
    }
    status = normalizeStatus(status, rowNo);
    LocalDate dueDate;
    try {
      dueDate = CsvImportSupport.parseFlexibleDate(required(raw, "due_date", rowNo));
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 due_date 日期格式错误");
    }
    return new CsvPayablePlanRow(rowNo,
        optional(raw, "contract_no"),
        required(raw, "supplier_name", rowNo),
        required(raw, "node_name", rowNo),
        dueDate,
        planAmount, paidAmount, status,
        optional(raw, "remark"),
        objectMapper.writeValueAsString(raw));
  }

  static String normalizeStatus(String status, int rowNo) {
    String value = status.trim().toLowerCase();
    if ("未付".equals(value) || "待支付".equals(value)) return "unpaid";
    if ("部分支付".equals(value) || "部分付款".equals(value)) return "partial";
    if ("已付".equals(value) || "已支付".equals(value)) return "paid";
    if (!ALLOWED_STATUSES.contains(value)) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR,
          "第 " + rowNo + " 行 status 不合法，仅支持 unpaid/partial/paid");
    }
    return value;
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"supplier_name", "node_name", "due_date", "plan_amount", "paid_amount", "status"}) {
      if (message.contains(field)) return field;
    }
    if (message.contains("日期")) return "due_date";
    if (message.contains("金额")) return "plan_amount";
    return "row";
  }

  private BigDecimal decimal(String value, String field, int rowNo) {
    try {
      return new BigDecimal(CsvImportSupport.normalizeAmount(value));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + field + " 格式错误");
    }
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
    aliases.put("suppliername", "supplier_name");
    aliases.put("nodename", "node_name");
    aliases.put("duedate", "due_date");
    aliases.put("planamount", "plan_amount");
    aliases.put("paidamount", "paid_amount");
    aliases.put("合同编号", "contract_no");
    aliases.put("供应商", "supplier_name");
    aliases.put("供应商名称", "supplier_name");
    aliases.put("收款方", "supplier_name");
    aliases.put("节点名称", "node_name");
    aliases.put("付款节点", "node_name");
    aliases.put("付款事项", "node_name");
    aliases.put("应付日期", "due_date");
    aliases.put("到期日期", "due_date");
    aliases.put("付款日期", "due_date");
    aliases.put("应付金额", "plan_amount");
    aliases.put("计划金额", "plan_amount");
    aliases.put("已付金额", "paid_amount");
    aliases.put("状态", "status");
    aliases.put("备注", "remark");
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }
}
