package com.bankforecast.receipt;

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
public class CsvReceiptParser {
  private final ObjectMapper objectMapper;

  public CsvReceiptParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CsvParseResult<CsvReceiptRow> parse(InputStream inputStream, int maxRows) {
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
      for (String key : new String[] {"receipt_no", "transaction_date", "amount"}) {
        if (!indexes.containsKey(key)) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "缺少必填列 " + key);
      }

      List<CsvReceiptRow> rows = new ArrayList<>();
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
          String currency = optional(raw, "currency");
          if (currency == null) currency = "CNY";
          rows.add(new CsvReceiptRow(rowNo,
              optional(raw, "bank_name"),
              required(raw, "receipt_no", rowNo),
              parseDate(raw, "print_date", rowNo),
              requiredDate(raw, "transaction_date", rowNo),
              optional(raw, "transaction_time"),
              currency,
              optional(raw, "payer_name"),
              optional(raw, "payer_account_last4"),
              optional(raw, "payee_name"),
              optional(raw, "payee_account_last4"),
              optional(raw, "payer_bank"),
              optional(raw, "payee_bank"),
              positive(raw, "amount", rowNo),
              optional(raw, "summary"),
              optional(raw, "transaction_no"),
              optional(raw, "channel"),
              optional(raw, "verification_code"),
              objectMapper.writeValueAsString(raw)));
        } catch (BusinessException ex) {
          errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
        }
      }
      return new CsvParseResult<>(rows, errors, rows.size() + errors.size());
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "回单文件解析失败");
    }
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"receipt_no", "transaction_date", "print_date", "amount"}) {
      if (message.contains(field)) return field;
    }
    if (message.contains("日期")) return "transaction_date";
    if (message.contains("金额")) return "amount";
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

  private LocalDate requiredDate(Map<String, String> raw, String key, int rowNo) {
    String value = required(raw, key, rowNo);
    LocalDate date = parseDateValue(value);
    if (date == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 日期格式错误");
    return date;
  }

  private LocalDate parseDate(Map<String, String> raw, String key, int rowNo) {
    String value = optional(raw, key);
    if (value == null) return null;
    LocalDate date = parseDateValue(value);
    if (date == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 日期格式错误");
    return date;
  }

  private LocalDate parseDateValue(String value) {
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); }
      catch (DateTimeParseException ignored) {
        // 尝试下一种回单导出日期格式
      }
    }
    return null;
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
    aliases.put("bankname", "bank_name");
    aliases.put("receiptno", "receipt_no");
    aliases.put("printdate", "print_date");
    aliases.put("transactiondate", "transaction_date");
    aliases.put("transactiontime", "transaction_time");
    aliases.put("payername", "payer_name");
    aliases.put("payeraccountlast4", "payer_account_last4");
    aliases.put("payeename", "payee_name");
    aliases.put("payeeaccountlast4", "payee_account_last4");
    aliases.put("payerbank", "payer_bank");
    aliases.put("payeebank", "payee_bank");
    aliases.put("transactionno", "transaction_no");
    aliases.put("verificationcode", "verification_code");
    aliases.put("银行名称", "bank_name");
    aliases.put("回单号", "receipt_no");
    aliases.put("回单编号", "receipt_no");
    aliases.put("打印日期", "print_date");
    aliases.put("交易日期", "transaction_date");
    aliases.put("交易时间", "transaction_time");
    aliases.put("币种", "currency");
    aliases.put("付款方户名", "payer_name");
    aliases.put("付款人", "payer_name");
    aliases.put("付款方账号后四位", "payer_account_last4");
    aliases.put("收款方户名", "payee_name");
    aliases.put("收款人", "payee_name");
    aliases.put("收款方账号后四位", "payee_account_last4");
    aliases.put("付款方开户行", "payer_bank");
    aliases.put("收款方开户行", "payee_bank");
    aliases.put("交易金额", "amount");
    aliases.put("金额", "amount");
    aliases.put("摘要", "summary");
    aliases.put("交易流水号", "transaction_no");
    aliases.put("流水号", "transaction_no");
    aliases.put("渠道", "channel");
    aliases.put("用途", "channel");
    aliases.put("验证码", "verification_code");
    aliases.put("电子验证码", "verification_code");
    return aliases.containsKey(normalized) ? aliases.get(normalized) : normalized;
  }
}
