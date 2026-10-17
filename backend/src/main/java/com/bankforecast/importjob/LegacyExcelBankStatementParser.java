package com.bankforecast.importjob;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

/**
 * 读取银行网银 Excel 97-2003（.xls，HSSF）流水文件。
 * 东亚银行企业网银导出 sheet 名为账号形态、宁波银行为通用 Sheet1，均不能用 sheet 名识别，
 * 只能按各自完整表头签名做内容识别（EAB 14 列、NBCB 15 列，两套签名独立判定、互不干扰）。
 */
@Component
public class LegacyExcelBankStatementParser {

  private static final String[] EAB_ENTERPRISE_HEADERS = {
      "生效日期", "交易日期", "交易时间", "支出金额", "收入金额", "对方账号", "对方户名",
      "对方银行名称", "余额", "成交汇率", "交易流水号", "摘要", "交易备注", "票据凭证号码"};
  private static final String[] NBCB_ENTERPRISE_HEADERS = {
      "流水号", "交易账号", "交易日期", "交易时间", "币种", "交易网点", "借方金额", "贷方金额",
      "账户余额", "对方账号", "对方户名", "对方开户行", "凭证号", "摘要", "备注"};
  private static final int HEADER_SCAN_LIMIT = 60;

  private enum LegacyFormat {
    EAB("东亚银行", "EAB1", EAB_ENTERPRISE_HEADERS),
    NBCB("宁波银行", "NBCB1", NBCB_ENTERPRISE_HEADERS);

    private final String bankName;
    private final String prefix;
    private final String[] headers;

    LegacyFormat(String bankName, String prefix, String[] headers) {
      this.bankName = bankName;
      this.prefix = prefix;
      this.headers = headers;
    }
  }

  private static class HeaderMatch {
    private final int rowIndex;
    private final LegacyFormat format;
    private HeaderMatch(int rowIndex, LegacyFormat format) {
      this.rowIndex = rowIndex;
      this.format = format;
    }
  }

  private final ObjectMapper objectMapper;

  public LegacyExcelBankStatementParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ExcelBankStatementParser.ExcelParseResult parse(InputStream inputStream, int maxRows) {
    try (HSSFWorkbook workbook = new HSSFWorkbook(inputStream)) {
      DataFormatter formatter = new DataFormatter();
      List<CsvBankStatementRow> rows = new ArrayList<>();
      List<CsvRowError> errors = new ArrayList<>();
      List<Map<String, Object>> templates = new ArrayList<>();
      int totalRows = 0;
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        String sheetName = sheet.getSheetName();
        HeaderMatch header = findHeaderRow(sheet, formatter);
        if (header == null) {
          templates.add(template(sheetName, null, "unsupported", "未找到支持的银行企业网银表头签名"));
          continue;
        }
        LegacyFormat format = header.format;
        templates.add(template(sheetName, format.bankName, "recognized", "表头已映射为统一流水字段"));
        Map<Integer, String> columnKeys = columnKeys(sheet.getRow(header.rowIndex), formatter, format);
        for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Map<String, String> raw = rawValues(sheet.getRow(rowIndex), columnKeys, formatter);
          if (isBlank(raw)) continue;
          totalRows++;
          if (totalRows > maxRows) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
          int rowNo = rowIndex + 1;
          if (isRepeatedHeader(raw) || isFooterRow(raw)) continue;
          try {
            rows.add(toRow(rowNo, raw, sheetName, format));
          } catch (BusinessException ex) {
            errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(),
                objectMapper.writeValueAsString(raw)));
          }
        }
      }
      boolean recognized = false;
      for (Map<String, Object> item : templates) recognized |= "recognized".equals(item.get("status"));
      if (!recognized) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "未识别到支持的银行流水模板");
      return new ExcelBankStatementParser.ExcelParseResult(rows, errors, totalRows, templates);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "Excel 文件解析失败");
    }
  }

  /** 按行归一化表头集合分别匹配各格式完整签名；两套签名独立判定，全部命中才识别。 */
  private HeaderMatch findHeaderRow(Sheet sheet, DataFormatter formatter) {
    int last = Math.min(sheet.getLastRowNum(), HEADER_SCAN_LIMIT);
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      Set<String> values = new HashSet<>();
      for (int column = 0; column < row.getLastCellNum(); column++) {
        values.add(CsvImportSupport.normalizeHeader(cellText(row, column, formatter)));
      }
      for (LegacyFormat format : LegacyFormat.values()) {
        boolean matched = true;
        for (String header : format.headers) {
          if (!values.contains(CsvImportSupport.normalizeHeader(header))) {
            matched = false;
            break;
          }
        }
        if (matched) return new HeaderMatch(rowIndex, format);
      }
    }
    return null;
  }

  /** 表头列索引 → 标准字段名；未映射列保留原始表头文本作为 raw_json 键。 */
  private Map<Integer, String> columnKeys(Row headerRow, DataFormatter formatter, LegacyFormat format) {
    Map<Integer, String> keys = new LinkedHashMap<>();
    for (int column = 0; column < headerRow.getLastCellNum(); column++) {
      String original = cellText(headerRow, column, formatter);
      String normalized = CsvImportSupport.normalizeHeader(original);
      String key = canonicalHeader(normalized, format);
      keys.put(column, key.isEmpty() ? original : key);
    }
    return keys;
  }

  private String canonicalHeader(String normalized, LegacyFormat format) {
    switch (format) {
      case EAB:
        if (normalized.equals("支出金额")) return "debit_amount";
        if (normalized.equals("收入金额")) return "credit_amount";
        if (normalized.equals("余额")) return "balance_after";
        if (normalized.equals("对方银行名称")) return "counterparty_bank";
        if (normalized.equals("交易备注")) return "remark";
        if (normalized.equals("交易流水号")) return "source_transaction_no";
        break;
      case NBCB:
        if (normalized.equals("借方金额")) return "debit_amount";
        if (normalized.equals("贷方金额")) return "credit_amount";
        if (normalized.equals("账户余额")) return "balance_after";
        if (normalized.equals("对方开户行")) return "counterparty_bank";
        if (normalized.equals("备注")) return "remark";
        if (normalized.equals("流水号")) return "source_transaction_no";
        break;
    }
    if (normalized.equals("交易日期")) return "transaction_date";
    if (normalized.equals("交易时间")) return "transaction_time";
    if (normalized.equals("对方账号")) return "counterparty_account";
    if (normalized.equals("对方户名")) return "counterparty_name";
    if (normalized.equals("摘要")) return "summary";
    return "";
  }

  private Map<String, String> rawValues(Row row, Map<Integer, String> columnKeys, DataFormatter formatter) {
    Map<String, String> raw = new LinkedHashMap<>();
    for (Map.Entry<Integer, String> entry : columnKeys.entrySet()) {
      if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
      raw.put(entry.getValue(), emptyAsNull(row == null ? null : cellText(row, entry.getKey(), formatter)));
    }
    return raw;
  }

  private String cellText(Row row, int column, DataFormatter formatter) {
    if (row == null || row.getCell(column) == null) return "";
    return formatter.formatCellValue(row.getCell(column)).trim();
  }

  /** 企业网银未使用的一侧金额或凭证号常以 "--" 占位，与空串一样视为空。 */
  private String emptyAsNull(String value) {
    if (value == null) return null;
    String text = value.trim();
    return text.isEmpty() || "--".equals(text) ? null : text;
  }

  private CsvBankStatementRow toRow(int rowNo, Map<String, String> raw, String sheetName, LegacyFormat format) {
    String sourceTransactionNo = raw.get("source_transaction_no");
    String dateText = required(raw, "transaction_date", rowNo);
    LocalDate transactionDate = parseDate(dateText, rowNo);
    BigDecimal debit = decimalOrNull(raw.get("debit_amount"), "debit_amount", rowNo);
    BigDecimal credit = decimalOrNull(raw.get("credit_amount"), "credit_amount", rowNo);
    String direction;
    BigDecimal amount;
    if (credit != null && credit.compareTo(BigDecimal.ZERO) > 0) {
      direction = "income";
      amount = credit;
    } else if (debit != null && debit.compareTo(BigDecimal.ZERO) > 0) {
      direction = "expense";
      amount = debit;
    } else if (debit == null && credit == null) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 不能为空");
    } else {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
    }
    BigDecimal balance = decimalOrNull(raw.get("balance_after"), "balance_after", rowNo);
    String summary = raw.get("summary");
    String transactionNo = enterpriseTransactionNo(raw, sourceTransactionNo, transactionDate, direction, amount, balance, format);
    Map<String, String> normalized = new LinkedHashMap<>(raw);
    normalized.put("transaction_no", transactionNo);
    normalized.put("transaction_date", dateText);
    normalized.put("amount", amount.toPlainString());
    normalized.put("direction", direction);
    normalized.put("source_bank", format.bankName);
    normalized.put("source_sheet", sheetName);
    normalized.put("source_transaction_no", sourceTransactionNo == null ? "" : sourceTransactionNo);
    try {
      return new CsvBankStatementRow(rowNo, transactionNo, transactionDate, direction, amount,
          balance, raw.get("counterparty_name"), summary, objectMapper.writeValueAsString(normalized));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行原始字段保存失败");
    }
  }

  /**
   * 标准流水号：source_transaction_no、transaction_time、transaction_date(ISO)、direction、
   * amount、balance_after、counterparty_account、counterparty_name、counterparty_bank、summary、remark。
   * 规范化与 ExcelBankStatementParser 完全一致，不含行号、文件名、任务 ID 或随机值；
   * 前缀按格式区分（EAB1/NBCB1）。
   */
  private String enterpriseTransactionNo(Map<String, String> raw, String sourceTransactionNo,
      LocalDate transactionDate, String direction, BigDecimal amount, BigDecimal balance, LegacyFormat format) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
    List<String> identity = List.of(
        normalizeText(sourceTransactionNo),
        normalizeText(raw.get("transaction_time")),
        transactionDate.toString(),
        direction,
        canonicalDecimal(amount),
        canonicalDecimal(balance),
        normalizeText(raw.get("counterparty_account")),
        normalizeText(raw.get("counterparty_name")),
        normalizeText(raw.get("counterparty_bank")),
        normalizeText(raw.get("summary")),
        normalizeText(raw.get("remark")));
    for (String field : identity) updateHash(digest, field);
    return format.prefix + "-" + java.util.HexFormat.of().formatHex(digest.digest());
  }

  private void updateHash(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private String canonicalDecimal(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private String normalizeText(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private String required(Map<String, String> raw, String key, int rowNo) {
    String value = raw.get(key);
    if (value == null || value.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空");
    }
    return value.trim();
  }

  private BigDecimal decimalOrNull(String value, String key, int rowNo) {
    if (value == null || value.trim().isEmpty()) return null;
    try {
      return new BigDecimal(CsvImportSupport.normalizeAmount(value));
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 格式错误");
    }
  }

  private LocalDate parseDate(String value, int rowNo) {
    try {
      return CsvImportSupport.parseFlexibleDate(value);
    } catch (DateTimeParseException ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误");
    }
  }

  private boolean isRepeatedHeader(Map<String, String> raw) {
    String date = raw.get("transaction_date");
    return date != null
        && CsvImportSupport.normalizeHeader(date).equals(CsvImportSupport.normalizeHeader("交易日期"));
  }

  private boolean isFooterRow(Map<String, String> raw) {
    String date = raw.get("transaction_date");
    if (date == null) return false;
    String text = date.trim();
    return "合计".equals(text) || "总计".equals(text) || "小计".equals(text);
  }

  private boolean isBlank(Map<String, String> raw) {
    for (String value : raw.values()) if (value != null && !value.trim().isEmpty()) return false;
    return true;
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"transaction_date", "amount", "debit_amount", "credit_amount", "balance_after"}) {
      if (message.contains(field)) return field;
    }
    return "row";
  }

  private Map<String, Object> template(String sheetName, String bankName, String status, String message) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sheet_name", sheetName);
    item.put("bank_name", bankName);
    item.put("status", status);
    item.put("message", message);
    return item;
  }
}
