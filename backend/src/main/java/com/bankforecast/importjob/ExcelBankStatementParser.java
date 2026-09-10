package com.bankforecast.importjob;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** 读取银行网银 Excel，并将不同银行字段映射为统一流水模型。 */
@Component
public class ExcelBankStatementParser {
  private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String[] SUPPORTED_BANKS = {"中国银行", "工商银行", "广发银行", "平安银行", "上海银行", "苏州银行"};
  private final ObjectMapper objectMapper;

  public ExcelBankStatementParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

  public ExcelParseResult parse(InputStream inputStream, int maxRows) {
    try {
      File temp = File.createTempFile("bank-statement-", ".xlsx");
      temp.deleteOnExit();
      Files.write(temp.toPath(), readAll(inputStream));
      try (ZipFile zip = new ZipFile(temp)) {
        List<String> sharedStrings = readSharedStrings(zip);
        List<SheetRef> sheets = readSheets(zip);
        List<CsvBankStatementRow> rows = new ArrayList<>();
        List<CsvRowError> errors = new ArrayList<>();
        List<Map<String, Object>> templates = new ArrayList<>();
        int totalRows = 0;
        for (SheetRef sheet : sheets) {
          Document document = parseXml(readEntry(zip, sheet.path));
          String bankName = detectBank(sheet.name);
          Header header = findHeader(document, sharedStrings);
          if (header == null) {
            templates.add(template(sheet.name, bankName, "unsupported", "未找到交易日期和金额表头"));
            continue;
          }
          if (bankName == null) {
            templates.add(template(sheet.name, null, "unsupported", "工作表名称无法识别为六家支持银行"));
            continue;
          }
          templates.add(template(sheet.name, bankName, "recognized", "表头已映射为统一流水字段"));
          NodeList rowNodes = document.getElementsByTagNameNS(MAIN_NS, "row");
          for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            int rowIndex = parseInt(row.getAttribute("r"), i + 1) - 1;
            if (rowIndex <= header.rowIndex) continue;
            Map<String, String> raw = rawValues(row, header, sharedStrings);
            if (isBlank(raw)) continue;
            totalRows++;
            if (totalRows > maxRows) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "导入行数超过上限");
            int rowNo = rowIndex + 1;
            if (isMetadataRow(raw)) continue;
            try { rows.add(toRow(rowNo, raw, bankName, sheet.name)); }
            catch (BusinessException ex) { errors.add(new CsvRowError(rowNo, fieldFromMessage(ex.getMessage()), ex.getMessage(), objectMapper.writeValueAsString(raw))); }
          }
        }
        if (templates.isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "Excel 文件没有可读取的工作表");
        boolean recognized = false;
        for (Map<String, Object> item : templates) recognized |= "recognized".equals(item.get("status"));
        if (!recognized) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "未识别到支持的银行流水模板");
        return new ExcelParseResult(rows, errors, totalRows, templates);
      }
    } catch (BusinessException ex) { throw ex; }
    catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "Excel 文件解析失败"); }
  }

  private CsvBankStatementRow toRow(int rowNo, Map<String, String> raw, String bankName, String sheetName) {
    String transactionNo = first(raw, "transaction_no", "凭证号", "流水号");
    if (transactionNo == null) transactionNo = "XLSX-" + bankName + "-" + rowNo;
    String dateText = required(raw, "transaction_date", rowNo);
    LocalDate transactionDate = parseDate(dateText, rowNo);
    BigDecimal debit = decimalOrNull(raw.get("debit_amount"), "debit_amount", rowNo);
    BigDecimal credit = decimalOrNull(raw.get("credit_amount"), "credit_amount", rowNo);
    if (debit == null && credit == null) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 amount 不能为空");
    boolean income = credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
    BigDecimal amount = income ? credit : debit;
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行金额必须大于 0");
    Map<String, String> normalized = new LinkedHashMap<>(raw);
    normalized.put("transaction_no", transactionNo); normalized.put("transaction_date", dateText);
    normalized.put("amount", amount.toPlainString()); normalized.put("direction", income ? "income" : "expense");
    normalized.put("source_bank", bankName); normalized.put("source_sheet", sheetName);
    try {
      return new CsvBankStatementRow(rowNo, transactionNo, transactionDate, income ? "income" : "expense", amount,
          decimalOrNull(raw.get("balance_after"), "balance_after", rowNo), raw.get("counterparty_name"), raw.get("summary"), objectMapper.writeValueAsString(normalized));
    } catch (Exception ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行原始字段保存失败"); }
  }

  private Header findHeader(Document document, List<String> sharedStrings) {
    NodeList rowNodes = document.getElementsByTagNameNS(MAIN_NS, "row");
    for (int i = 0; i < rowNodes.getLength() && i <= 60; i++) {
      Element row = (Element) rowNodes.item(i);
      Map<String, Integer> indexes = new HashMap<>();
      NodeList cells = row.getElementsByTagNameNS(MAIN_NS, "c");
      for (int j = 0; j < cells.getLength(); j++) {
        Element cell = (Element) cells.item(j);
        String key = canonicalHeader(cellText(cell, sharedStrings));
        if (!key.isEmpty()) indexes.put(key, columnIndex(cell.getAttribute("r")));
      }
      if (indexes.containsKey("transaction_date") && (indexes.containsKey("debit_amount") || indexes.containsKey("credit_amount"))) return new Header(parseInt(row.getAttribute("r"), i + 1) - 1, indexes);
    }
    return null;
  }

  private Map<String, String> rawValues(Element row, Header header, List<String> sharedStrings) {
    Map<Integer, String> values = new HashMap<>();
    NodeList cells = row.getElementsByTagNameNS(MAIN_NS, "c");
    for (int i = 0; i < cells.getLength(); i++) { Element cell = (Element) cells.item(i); values.put(columnIndex(cell.getAttribute("r")), cellText(cell, sharedStrings)); }
    Map<String, String> raw = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : header.indexes.entrySet()) raw.put(entry.getKey(), values.get(entry.getValue()));
    return raw;
  }

  private String cellText(Element cell, List<String> sharedStrings) {
    String type = cell.getAttribute("t");
    if ("inlineStr".equals(type)) return textOf(cell, "t");
    String value = textOf(cell, "v");
    if ("s".equals(type) && !value.isEmpty()) { int index = parseInt(value, -1); return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : ""; }
    return value.trim();
  }

  private List<String> readSharedStrings(ZipFile zip) throws Exception {
    ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
    if (entry == null) return Collections.emptyList();
    Document document = parseXml(zip.getInputStream(entry));
    NodeList items = document.getElementsByTagNameNS(MAIN_NS, "si");
    List<String> result = new ArrayList<>();
    for (int i = 0; i < items.getLength(); i++) {
      NodeList texts = ((Element) items.item(i)).getElementsByTagNameNS(MAIN_NS, "t");
      StringBuilder value = new StringBuilder();
      for (int j = 0; j < texts.getLength(); j++) value.append(texts.item(j).getTextContent());
      result.add(value.toString());
    }
    return result;
  }

  private List<SheetRef> readSheets(ZipFile zip) throws Exception {
    Document workbook = parseXml(readEntry(zip, "xl/workbook.xml"));
    Document relationships = parseXml(readEntry(zip, "xl/_rels/workbook.xml.rels"));
    Map<String, String> targets = new HashMap<>();
    NodeList rels = relationships.getElementsByTagName("Relationship");
    for (int i = 0; i < rels.getLength(); i++) { Element rel = (Element) rels.item(i); targets.put(rel.getAttribute("Id"), rel.getAttribute("Target")); }
    List<SheetRef> result = new ArrayList<>();
    NodeList sheets = workbook.getElementsByTagNameNS(MAIN_NS, "sheet");
    for (int i = 0; i < sheets.getLength(); i++) { Element sheet = (Element) sheets.item(i); String target = targets.get(sheet.getAttributeNS(REL_NS, "id")); if (target != null) result.add(new SheetRef(sheet.getAttribute("name"), normalizePath(target))); }
    return result;
  }

  private String normalizePath(String target) { return target.startsWith("/") ? target.substring(1) : "xl/" + target.replace("../", ""); }
  private Document parseXml(byte[] bytes) throws Exception { return parseXml(new java.io.ByteArrayInputStream(bytes)); }
  private Document parseXml(InputStream input) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    return factory.newDocumentBuilder().parse(input);
  }
  private byte[] readEntry(ZipFile zip, String name) throws IOException { ZipEntry entry = zip.getEntry(name); if (entry == null) throw new IOException("缺少 Excel 工作表"); try (InputStream input = zip.getInputStream(entry)) { return readAll(input); } }
  private byte[] readAll(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count); return output.toByteArray(); }
  private String textOf(Element element, String name) { NodeList nodes = element.getElementsByTagNameNS(MAIN_NS, name); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim(); }
  private int columnIndex(String reference) { int result = 0; for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) result = result * 26 + Character.toUpperCase(reference.charAt(i)) - 'A' + 1; return result - 1; }
  private int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ex) { return fallback; } }
  private String canonicalHeader(String value) { String normalized = CsvImportSupport.normalizeHeader(value); if (normalized.contains("交易日期") || normalized.equals("交易日") || normalized.equals("日期")) return "transaction_date"; if (normalized.contains("凭证号") || normalized.contains("交易流水号") || normalized.equals("流水号")) return "transaction_no"; if (normalized.contains("借方金额") || normalized.contains("支出金额")) return "debit_amount"; if (normalized.contains("贷方金额") || normalized.contains("收入金额")) return "credit_amount"; if (normalized.equals("余额") || normalized.contains("交易后余额")) return "balance_after"; if (normalized.contains("对方户名") || normalized.equals("对手方")) return "counterparty_name"; if (normalized.contains("摘要") || normalized.contains("交易用途") || normalized.equals("备注")) return "summary"; return normalized; }
  private String detectBank(String sheetName) { for (String bank : SUPPORTED_BANKS) if (sheetName != null && sheetName.contains(bank)) return bank; return null; }
  private Map<String, Object> template(String sheetName, String bankName, String status, String message) { Map<String, Object> item = new LinkedHashMap<>(); item.put("sheet_name", sheetName); item.put("bank_name", bankName); item.put("status", status); item.put("message", message); return item; }
  private String first(Map<String, String> raw, String... keys) { for (String key : keys) { String value = raw.get(key); if (value != null && !value.trim().isEmpty()) return value.trim(); } return null; }
  private String required(Map<String, String> raw, String key, int rowNo) { String value = raw.get(key); if (value == null || value.trim().isEmpty()) throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 不能为空"); return value.trim(); }
  private BigDecimal decimalOrNull(String value, String key, int rowNo) { if (value == null || value.trim().isEmpty()) return null; try { return new BigDecimal(CsvImportSupport.normalizeAmount(value)); } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行 " + key + " 格式错误"); } }
  private LocalDate parseDate(String value, int rowNo) { for (DateTimeFormatter formatter : new DateTimeFormatter[] {DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd")}) { try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { } } throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "第 " + rowNo + " 行日期格式错误"); }
  private boolean isBlank(Map<String, String> raw) { for (String value : raw.values()) if (value != null && !value.trim().isEmpty()) return false; return true; }
  private boolean isMetadataRow(Map<String, String> raw) { String date = raw.get("transaction_date"); if (date == null || date.trim().isEmpty()) return false; if (first(raw, "debit_amount", "credit_amount") != null) return false; for (String key : new String[] {"transaction_no", "balance_after", "counterparty_name", "summary"}) if (first(raw, key) != null) return false; return !isDate(date); }
  private boolean isDate(String value) { try { parseDate(value, 0); return true; } catch (BusinessException ex) { return false; } }
  private String fieldFromMessage(String message) { if (message == null) return "row"; for (String field : new String[] {"transaction_date", "amount", "debit_amount", "credit_amount", "balance_after"}) if (message.contains(field)) return field; return "row"; }

  private static class Header { private final int rowIndex; private final Map<String, Integer> indexes; private Header(int rowIndex, Map<String, Integer> indexes) { this.rowIndex = rowIndex; this.indexes = indexes; } }
  private static class SheetRef { private final String name; private final String path; private SheetRef(String name, String path) { this.name = name; this.path = path; } }
  public static class ExcelParseResult {
    private final List<CsvBankStatementRow> rows; private final List<CsvRowError> errors; private final int totalRows; private final List<Map<String, Object>> templates;
    public ExcelParseResult(List<CsvBankStatementRow> rows, List<CsvRowError> errors, int totalRows, List<Map<String, Object>> templates) { this.rows = rows; this.errors = errors; this.totalRows = totalRows; this.templates = templates; }
    public List<CsvBankStatementRow> getRows() { return rows; } public List<CsvRowError> getErrors() { return errors; } public int getTotalRows() { return totalRows; } public List<Map<String, Object>> getTemplates() { return templates; }
  }
}
