package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankforecast.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;

class LegacyExcelBankStatementParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final LegacyExcelBankStatementParser parser = new LegacyExcelBankStatementParser(objectMapper);

  @Test
  void recognizesEabSignatureRegardlessOfSheetNameAndMapsFields() throws Exception {
    String sheetName = "998877665544.csv";
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook(sheetName, sampleDataRows()));

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("东亚银行", result.getTemplates().get(0).get("bank_name"));
    assertEquals(7, result.getTotalRows());
    assertEquals(7, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());

    int income = 0;
    int expense = 0;
    Set<String> standardNos = new HashSet<>();
    Set<String> sourceNos = new HashSet<>();
    for (int i = 0; i < result.getRows().size(); i++) {
      CsvBankStatementRow row = result.getRows().get(i);
      if ("income".equals(row.getDirection())) income++;
      if ("expense".equals(row.getDirection())) expense++;
      assertFalse(row.getCounterpartyName() == null || row.getCounterpartyName().isBlank());
      assertFalse(row.getSummary() == null || row.getSummary().isBlank());
      assertEquals(69, row.getTransactionNo().length());
      assertTrue(row.getTransactionNo().matches("EAB1-[0-9a-f]{64}"));
      assertEquals("EAB1|" + row.getTransactionNo(), WeakFingerprint.of(row));
      standardNos.add(row.getTransactionNo());

      JsonNode raw = objectMapper.readTree(row.getRawJson());
      List<String> source = sampleDataRows().get(i);
      assertEquals(row.getTransactionNo(), raw.path("transaction_no").asText());
      assertEquals(source.get(10), raw.path("source_transaction_no").asText());
      assertEquals(source.get(2), raw.path("transaction_time").asText());
      assertEquals(source.get(1), raw.path("transaction_date").asText());
      assertEquals(source.get(5), raw.path("counterparty_account").asText());
      assertEquals(source.get(6), raw.path("counterparty_name").asText());
      assertEquals(source.get(7), raw.path("counterparty_bank").asText());
      assertEquals(source.get(11), raw.path("summary").asText());
      assertEquals(source.get(12), raw.path("remark").asText());
      assertEquals(source.get(8), raw.path("balance_after").asText());
      assertEquals(source.get(0), raw.path("生效日期").asText());
      assertEquals(source.get(9), raw.path("成交汇率").asText());
      assertEquals(source.get(13), raw.path("票据凭证号码").asText());
      assertEquals("东亚银行", raw.path("source_bank").asText());
      assertEquals(sheetName, raw.path("source_sheet").asText());
      sourceNos.add(raw.path("source_transaction_no").asText());
    }
    assertEquals(3, income);
    assertEquals(4, expense);
    assertEquals(7, standardNos.size());
    assertEquals(5, sourceNos.size());

    CsvBankStatementRow first = result.getRows().get(0);
    assertEquals("expense", first.getDirection());
    assertEquals("100.10", first.getAmount().toPlainString());
    assertEquals("899.90", first.getBalanceAfter().toPlainString());
    assertEquals("2026-02-06", first.getTransactionDate().toString());
  }

  @Test
  void doubleDashMeansEmptyForUnusedAmountSide() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("sheet1", sampleDataRows()));
    JsonNode expenseRaw = objectMapper.readTree(result.getRows().get(0).getRawJson());
    assertTrue(expenseRaw.path("credit_amount").isNull());
    JsonNode incomeRaw = objectMapper.readTree(result.getRows().get(1).getRawJson());
    assertTrue(incomeRaw.path("debit_amount").isNull());
    assertEquals("income", result.getRows().get(1).getDirection());
    assertEquals("200.20", result.getRows().get(1).getAmount().toPlainString());
  }

  @Test
  void transactionNosAreStableAcrossParsingAndRowOrder() {
    byte[] workbook = workbook("sheet1", sampleDataRows());
    List<String> original = transactionNos(parse(workbook));
    List<String> repeated = transactionNos(parse(workbook));
    assertEquals(original, repeated);

    List<List<String>> reversedRows = sampleDataRows();
    Collections.reverse(reversedRows);
    List<String> reversed = transactionNos(parse(workbook("sheet1", reversedRows)));
    Collections.sort(original);
    Collections.sort(reversed);
    assertEquals(original, reversed);

    List<String> sourceRow = sampleDataRows().get(0);
    ExcelBankStatementParser.ExcelParseResult duplicates = parse(workbook("sheet1",
        List.of(sourceRow, new ArrayList<>(sourceRow))));
    assertEquals(duplicates.getRows().get(0).getTransactionNo(),
        duplicates.getRows().get(1).getTransactionNo());
  }

  @Test
  void sameSourceNoWithDifferentAmountOrCounterpartyGetsDifferentStandardNo() {
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> changedAmount = new ArrayList<>(sourceRow);
    changedAmount.set(3, "100.11");
    List<String> changedCounterparty = new ArrayList<>(sourceRow);
    changedCounterparty.set(6, "对手乙");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("sheet1",
        List.of(sourceRow, changedAmount, changedCounterparty)));
    assertEquals(3, result.getRows().size());
    Set<String> standardNos = new HashSet<>();
    for (CsvBankStatementRow row : result.getRows()) standardNos.add(row.getTransactionNo());
    assertEquals(3, standardNos.size());
  }

  @Test
  void fixedTransactionNoAndEveryIdentityFieldChangesIt() {
    String expected = "EAB1-2e864a31b8f31665e0ec8e68cc76b13af81de8996599782c79a022682e033638";
    List<String> sourceRow = sampleDataRows().get(0);
    assertEquals(expected, parse(workbook("sheet1", List.of(sourceRow))).getRows().get(0).getTransactionNo());

    int[] identityColumns = {1, 2, 3, 5, 6, 7, 8, 10, 11, 12};
    String[] replacements = {
        "2026-02-07", "09:00:02", "888.80", "CP-CHANGED", "对手变更", "银行变更",
        "7777.70", "SRC-CHANGED", "摘要变更", "备注变更"};
    for (int i = 0; i < identityColumns.length; i++) {
      List<String> changed = new ArrayList<>(sourceRow);
      changed.set(identityColumns[i], replacements[i]);
      String changedTransactionNo = parse(workbook("sheet1", List.of(changed)))
          .getRows().get(0).getTransactionNo();
      assertNotEquals(expected, changedTransactionNo, "身份字段列 " + identityColumns[i] + " 未参与标准号");
    }
  }

  @Test
  void canonicalIdentityFieldsProduceSameTransactionNo() {
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> scaledAmount = new ArrayList<>(sourceRow);
    scaledAmount.set(3, "100.1");
    List<String> whitespace = new ArrayList<>(sourceRow);
    whitespace.set(6, "  对手甲  ");
    whitespace.set(2, "09:00:01  ");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("sheet1",
        List.of(sourceRow, scaledAmount, whitespace)));
    assertEquals(3, result.getRows().size());
    String expected = result.getRows().get(0).getTransactionNo();
    assertEquals(expected, result.getRows().get(1).getTransactionNo());
    assertEquals(expected, result.getRows().get(2).getTransactionNo());
  }

  @Test
  void invalidAmountAndMissingDateAreRowErrors() {
    List<String> valid = sampleDataRows().get(0);
    List<String> blankAmount = new ArrayList<>(valid);
    blankAmount.set(3, "--");
    blankAmount.set(4, "--");
    List<String> zeroAmount = new ArrayList<>(valid);
    zeroAmount.set(3, "0");
    List<String> malformedAmount = new ArrayList<>(valid);
    malformedAmount.set(3, "not-a-number");
    List<String> missingDate = new ArrayList<>(valid);
    missingDate.set(1, "");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("sheet1",
        List.of(valid, blankAmount, zeroAmount, malformedAmount, missingDate)));
    assertEquals(1, result.getRows().size());
    assertEquals(4, result.getErrors().size());
    assertEquals(5, result.getTotalRows());
    assertEquals("amount", result.getErrors().get(0).getField());
    assertEquals("row", result.getErrors().get(1).getField());
    assertEquals("amount", result.getErrors().get(2).getField());
    assertEquals("transaction_date", result.getErrors().get(3).getField());
  }

  @Test
  void incompleteSignatureIsRejected() {
    List<String> incompleteHeader = new ArrayList<>(enterpriseHeader());
    incompleteHeader.remove(2);
    List<String> row = new ArrayList<>(sampleDataRows().get(0));
    row.remove(2);
    assertThrows(BusinessException.class,
        () -> parse(workbook("sheet1", List.of(row), incompleteHeader)));

    List<String> genericHeader = List.of("交易日期", "收入", "支出", "流水号");
    List<String> genericRow = List.of("2026-02-06", "10.00", "", "SRC-GENERIC");
    assertThrows(BusinessException.class,
        () -> parse(workbook("sheet1", List.of(genericRow), genericHeader)));
  }

  @Test
  void repeatedHeaderAndFooterRowsAreSkipped() {
    List<String> valid = sampleDataRows().get(0);
    List<String> repeatedHeader = enterpriseHeader();
    List<String> footer = new ArrayList<>(Collections.nCopies(14, ""));
    footer.set(1, "合计");
    footer.set(3, "600.10");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("sheet1",
        List.of(valid, repeatedHeader, footer)));
    assertEquals(1, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals(3, result.getTotalRows());
  }

  private ExcelBankStatementParser.ExcelParseResult parse(byte[] content) {
    return parser.parse(new ByteArrayInputStream(content), 1000);
  }

  private List<String> transactionNos(ExcelBankStatementParser.ExcelParseResult result) {
    List<String> transactionNos = new ArrayList<>();
    for (CsvBankStatementRow row : result.getRows()) transactionNos.add(row.getTransactionNo());
    return transactionNos;
  }

  private byte[] workbook(String sheetName, List<List<String>> dataRows) {
    return workbook(sheetName, dataRows, enterpriseHeader());
  }

  private byte[] workbook(String sheetName, List<List<String>> dataRows, List<String> header) {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 1; i <= 4; i++) rows.add(List.of("账户信息说明" + i));
    rows.add(header);
    rows.addAll(dataRows);
    try (HSSFWorkbook workbook = new HSSFWorkbook()) {
      Sheet sheet = workbook.createSheet(sheetName);
      for (int i = 0; i < rows.size(); i++) {
        Row row = sheet.createRow(i);
        for (int j = 0; j < rows.get(i).size(); j++) row.createCell(j).setCellValue(rows.get(i).get(j));
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<String> enterpriseHeader() {
    return new ArrayList<>(Arrays.asList("生效日期", "交易日期", "交易时间", "支出金额", "收入金额", "对方账号",
        "对方户名", "对方银行名称", "余额", "成交汇率", "交易流水号", "摘要", "交易备注", "票据凭证号码"));
  }

  private List<List<String>> sampleDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(Arrays.asList("2026-02-06", "2026-02-06", "09:00:01", "100.10", "--", "CP-001", "对手甲",
        "东亚银行上海分行", "899.90", "1.0000", "EAB-SRC-001", "采购付款", "货款", "VP-0001"));
    rows.add(Arrays.asList("2026-02-07", "2026-02-07", "10:00:02", "--", "200.20", "CP-002", "对手乙",
        "东亚银行上海分行", "1100.10", "1.0000", "EAB-SRC-002", "销售回款", "回款", "VP-0002"));
    rows.add(Arrays.asList("2026-02-08", "2026-02-08", "11:00:03", "300.00", "--", "CP-003", "对手丙",
        "东亚银行深圳分行", "800.10", "1.0000", "EAB-SRC-003", "服务费", "服务费", "VP-0003"));
    rows.add(Arrays.asList("2026-02-09", "2026-02-09", "12:00:04", "--", "400.00", "CP-004", "对手丁",
        "东亚银行深圳分行", "1200.10", "1.0000", "EAB-SRC-004", "项目回款", "回款", "VP-0004"));
    rows.add(Arrays.asList("2026-02-10", "2026-02-10", "13:00:05", "500.00", "--", "CP-005", "对手戊",
        "东亚银行广州分行", "700.10", "1.0000", "EAB-SRC-005", "原摘要", "付款", "VP-0005"));
    rows.add(Arrays.asList("2026-02-10", "2026-02-10", "14:00:06", "500.00", "--", "CP-005", "对手戊",
        "东亚银行广州分行", "700.10", "1.0000", "EAB-SRC-005", "原摘要", "付款", "VP-0005"));
    rows.add(Arrays.asList("2026-02-10", "2026-02-10", "15:00:07", "--", "600.00", "CP-005", "对手戊",
        "东亚银行广州分行", "1300.10", "1.0000", "EAB-SRC-005", "新摘要", "回款", "VP-0005"));
    return rows;
  }
}
