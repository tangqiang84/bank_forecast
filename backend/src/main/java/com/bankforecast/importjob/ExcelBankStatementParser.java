package com.bankforecast.importjob;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** 读取银行网银 Excel，并将不同银行字段映射为统一流水模型。 */
@Component
public class ExcelBankStatementParser {
  private static final String[] SUPPORTED_BANKS = {"中国银行", "工商银行", "广发银行", "平安银行", "上海银行", "苏州银行"};

  private final ObjectMapper objectMapper;

  public ExcelBankStatementParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ExcelParseResult parse(InputStream inputStream, int maxRows) {
    try (Workbook workbook = new XSSFWorkbook(inputStream)) {
      List<CsvBankStatementRow> rows = new ArrayList<>();
      List<CsvRowError> errors = new ArrayList<>();
      List<Map<String, Object>> templates = new ArrayList<>();
      int totalRows = 0;
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        Sheet sheet = workbook.getSheetAt(i);
        String bankName = detectBank(sheet.getSheetName());
        Header header = findHeader(sheet);
        if (header == null) {
          templates.add(template(sheet.getSheetName(), bankName, "unsupported", "未找到交易日期和金额表头"));
          continue;
        }
        if (bankName == null) {
          templates.add(template(sheet.getSheetName(), null, "unsupported", "工作表名称无法识别为六家支持银行"));
          continue;
        }
        templates.add(template(sheet.getSheetName(), bankName, "recognized", "表头已映射为统一流水字段"));
        for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (isBlank(row)) continue;
          totalRows++;
          if (totalRows > maxRows) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
          int rowNo = rowIndex + 1;
          Map<String, String> raw = rawValues(row, header);
          if (isMetadataRow(raw)) continue;
          try {
            rows.add(toRow(rowNo, raw, bankName, sheet.getSheetName()));
          } catch (BusinessException ex) {
            errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw)));
          }
        }
      }
      if (templates.isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "Excel 文件没有可读取的工作表");
      boolean recognized = false;
      for (Map<String, Object> item : templates) recognized |= "recognized".equals(item.get("status"));
      if (!recognized) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "未识别到支持的银行流水模板");
      return new ExcelParseResult(rows, errors, totalRows, templates);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "Excel 文件解析失败");
    }
  }

  private CsvBankStatementRow toRow(int rowNo, Map<String, String> raw, String bankName, String sheetName) {
    String transactionNo = first(raw, "transaction_no", "凭证号", "流水号");
    if (transactionNo == null) transactionNo = "XLSX-" + bankName + "-" + rowNo;
    String dateText = required(raw, "transaction_date", rowNo);
    LocalDate transactionDate = parseDate(dateText, rowNo);
    BigDecimal debit = decimalOrNull(raw.get("debit_amount"), "debit_amount", rowNo);
    BigDecimal credit = decimalOrNull(raw.get("credit_amount"), "credit_amount", rowNo);
    if (debit == null && credit == null) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 不能为空");
    }
    boolean income = credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
    BigDecimal amount = income ? credit : debit;
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
    }
    Map<String, String> normalized = new LinkedHashMap<>(raw);
    normalized.put("transaction_no", transactionNo);
    normalized.put("transaction_date", dateText);
    normalized.put("amount", amount.toPlainString());
    normalized.put("direction", income ? "income" : "expense");
    normalized.put("source_bank", bankName);
    normalized.put("source_sheet", sheetName);
    try {
      return new CsvBankStatementRow(rowNo, transactionNo, transactionDate,
          income ? "income" : "expense", amount,
          decimalOrNull(raw.get("balance_after"), "balance_after", rowNo),
          raw.get("counterparty_name"), raw.get("summary"), objectMapper.writeValueAsString(normalized));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行原始字段保存失败");
    }
  }

  private Header findHeader(Sheet sheet) {
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 60); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      Map<String, Integer> indexes = new HashMap<>();
      for (Cell cell : row) {
        String key = canonicalHeader(text(cell));
        if (!key.isEmpty()) indexes.put(key, cell.getColumnIndex());
      }
      if (indexes.containsKey("transaction_date")
          && (indexes.containsKey("debit_amount") || indexes.containsKey("credit_amount"))) {
        return new Header(rowIndex, indexes);
      }
    }
    return null;
  }

  private Map<String, String> rawValues(Row row, Header header) {
    Map<String, String> raw = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : header.indexes.entrySet()) {
      raw.put(entry.getKey(), text(row.getCell(entry.getValue())));
    }
    return raw;
  }

  private String canonicalHeader(String value) {
    String normalized = CsvImportSupport.normalizeHeader(value);
    if (normalized.contains("交易日期") || normalized.equals("交易日") || normalized.equals("日期")) return "transaction_date";
    if (normalized.contains("记账日期") || normalized.contains("入账日期")) return "booking_date";
    if (normalized.contains("凭证号") || normalized.contains("交易流水号") || normalized.equals("流水号")) return "transaction_no";
    if (normalized.contains("借方金额") || normalized.contains("支出金额")) return "debit_amount";
    if (normalized.contains("贷方金额") || normalized.contains("收入金额")) return "credit_amount";
    if (normalized.equals("余额") || normalized.contains("交易后余额")) return "balance_after";
    if (normalized.contains("对方户名") || normalized.equals("对手方")) return "counterparty_name";
    if (normalized.contains("摘要") || normalized.contains("交易用途") || normalized.equals("备注")) return "summary";
    if (normalized.contains("交易时间")) return "transaction_time";
    if (normalized.contains("收支方向") || normalized.contains("借贷标志")) return "direction";
    return normalized;
  }

  private String detectBank(String sheetName) {
    for (String bank : SUPPORTED_BANKS) if (sheetName != null && sheetName.contains(bank)) return bank;
    return null;
  }

  private Map<String, Object> template(String sheetName, String bankName, String status, String message) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sheet_name", sheetName);
    item.put("bank_name", bankName);
    item.put("status", status);
    item.put("message", message);
    return item;
  }

  private String first(Map<String, String> raw, String... keys) {
    for (String key : keys) {
      String value = raw.get(key);
      if (value != null && !value.trim().isEmpty()) return value.trim();
    }
    return null;
  }

  private String required(Map<String, String> raw, String key, int rowNo) {
    String value = raw.get(key);
    if (value == null || value.trim().isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空");
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
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { }
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误");
  }

  private String text(Cell cell) {
    if (cell == null) return "";
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      return cell.getLocalDateTimeCellValue().toLocalDate().toString();
    }
    return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
  }

  private boolean isBlank(Row row) {
    if (row == null) return true;
    for (Cell cell : row) if (!text(cell).isEmpty()) return false;
    return true;
  }

  private boolean isMetadataRow(Map<String, String> raw) {
    String date = raw.get("transaction_date");
    if (date == null || date.trim().isEmpty()) return false;
    if (raw.get("debit_amount") != null && !raw.get("debit_amount").trim().isEmpty()) return false;
    if (raw.get("credit_amount") != null && !raw.get("credit_amount").trim().isEmpty()) return false;
    for (String key : new String[] {"transaction_no", "balance_after", "counterparty_name", "summary", "booking_date"}) {
      String value = raw.get(key);
      if (value != null && !value.trim().isEmpty()) return false;
    }
    return !isDate(date);
  }

  private boolean isDate(String value) {
    for (DateTimeFormatter formatter : new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd")}) {
      try { LocalDate.parse(value, formatter); return true; } catch (DateTimeParseException ignored) { }
    }
    return false;
  }

  private String fieldFromMessage(String message) {
    if (message == null) return "row";
    for (String field : new String[] {"transaction_date", "amount", "debit_amount", "credit_amount", "balance_after"}) {
      if (message.contains(field)) return field;
    }
    return "row";
  }

  private static class Header {
    private final int rowIndex;
    private final Map<String, Integer> indexes;

    private Header(int rowIndex, Map<String, Integer> indexes) {
      this.rowIndex = rowIndex;
      this.indexes = indexes;
    }
  }

  public static class ExcelParseResult {
    private final List<CsvBankStatementRow> rows;
    private final List<CsvRowError> errors;
    private final int totalRows;
    private final List<Map<String, Object>> templates;

    public ExcelParseResult(List<CsvBankStatementRow> rows, List<CsvRowError> errors, int totalRows,
        List<Map<String, Object>> templates) {
      this.rows = rows; this.errors = errors; this.totalRows = totalRows; this.templates = templates;
    }

    public List<CsvBankStatementRow> getRows() { return rows; }
    public List<CsvRowError> getErrors() { return errors; }
    public int getTotalRows() { return totalRows; }
    public List<Map<String, Object>> getTemplates() { return templates; }
  }
}
