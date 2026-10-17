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

class NbcbEnterpriseXlsParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final LegacyExcelBankStatementParser parser = new LegacyExcelBankStatementParser(objectMapper);

  @Test
  void recognizesNbcbSignatureAndMapsFields() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1", sampleDataRows()));

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("宁波银行", result.getTemplates().get(0).get("bank_name"));
    assertEquals(4, result.getTotalRows());
    assertEquals(4, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());

    int income = 0;
    int expense = 0;
    Set<String> standardNos = new HashSet<>();
    for (int i = 0; i < result.getRows().size(); i++) {
      CsvBankStatementRow row = result.getRows().get(i);
      if ("income".equals(row.getDirection())) income++;
      if ("expense".equals(row.getDirection())) expense++;
      assertFalse(row.getCounterpartyName() == null || row.getCounterpartyName().isBlank());
      assertFalse(row.getSummary() == null || row.getSummary().isBlank());
      assertEquals(70, row.getTransactionNo().length());
      assertTrue(row.getTransactionNo().matches("NBCB1-[0-9a-f]{64}"));
      assertEquals("NBCB1|" + row.getTransactionNo(), WeakFingerprint.of(row));
      standardNos.add(row.getTransactionNo());

      JsonNode raw = objectMapper.readTree(row.getRawJson());
      List<String> source = sampleDataRows().get(i);
      assertEquals(row.getTransactionNo(), raw.path("transaction_no").asText());
      assertEquals(source.get(0), raw.path("source_transaction_no").asText());
      assertEquals(source.get(2), raw.path("transaction_date").asText());
      assertEquals(source.get(3), raw.path("transaction_time").asText());
      assertEquals(source.get(9), raw.path("counterparty_account").asText());
      assertEquals(source.get(10), raw.path("counterparty_name").asText());
      assertEquals(source.get(11), raw.path("counterparty_bank").asText());
      assertEquals(source.get(13), raw.path("summary").asText());
      assertEquals(source.get(1), raw.path("交易账号").asText());
      assertEquals(source.get(4), raw.path("币种").asText());
      assertEquals(source.get(5), raw.path("交易网点").asText());
      assertTrue(raw.path("凭证号").isNull());
      assertEquals("宁波银行", raw.path("source_bank").asText());
      assertEquals("Sheet1", raw.path("source_sheet").asText());
    }
    assertEquals(1, income);
    assertEquals(3, expense);
    assertEquals(4, standardNos.size());

    CsvBankStatementRow first = result.getRows().get(0);
    assertEquals("expense", first.getDirection());
    assertEquals("100.10", first.getAmount().toPlainString());
    assertEquals("899.90", first.getBalanceAfter().toPlainString());
    assertEquals("2026-02-06", first.getTransactionDate().toString());
  }

  @Test
  void zeroOrBlankUnusedAmountSideJudgedCorrectly() {
    List<String> zeroUnused = nbcbRow("NBCB-SRC-Z1", "2026-02-06", "09:00:01", "100.10", "0.00",
        "899.90", "CP-001", "对手甲", "宁波银行海曙支行", "采购付款", "货款");
    List<String> blankUnused = nbcbRow("NBCB-SRC-Z2", "2026-02-07", "10:00:02", "", "200.20",
        "1100.10", "CP-002", "对手乙", "宁波银行海曙支行", "销售回款", "回款");
    List<String> bothNonPositive = nbcbRow("NBCB-SRC-Z3", "2026-02-08", "11:00:03", "0.00", "",
        "1100.10", "CP-003", "对手丙", "宁波银行江北支行", "异常行", "备注");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
        List.of(zeroUnused, blankUnused, bothNonPositive)));
    assertEquals(2, result.getRows().size());
    assertEquals(1, result.getErrors().size());
    assertEquals("expense", result.getRows().get(0).getDirection());
    assertEquals("100.10", result.getRows().get(0).getAmount().toPlainString());
    assertEquals("income", result.getRows().get(1).getDirection());
    assertEquals("200.20", result.getRows().get(1).getAmount().toPlainString());
    assertTrue(result.getErrors().get(0).getMessage().contains("金额必须大于 0"));
  }

  @Test
  void transactionNosAreStableAcrossParsingAndRowOrder() {
    byte[] workbook = workbook("Sheet1", sampleDataRows());
    List<String> original = transactionNos(parse(workbook));
    List<String> repeated = transactionNos(parse(workbook));
    assertEquals(original, repeated);

    List<List<String>> reversedRows = sampleDataRows();
    Collections.reverse(reversedRows);
    List<String> reversed = transactionNos(parse(workbook("Sheet1", reversedRows)));
    Collections.sort(original);
    Collections.sort(reversed);
    assertEquals(original, reversed);

    List<String> sourceRow = sampleDataRows().get(0);
    ExcelBankStatementParser.ExcelParseResult duplicates = parse(workbook("Sheet1",
        List.of(sourceRow, new ArrayList<>(sourceRow))));
    assertEquals(duplicates.getRows().get(0).getTransactionNo(),
        duplicates.getRows().get(1).getTransactionNo());
  }

  @Test
  void sameSourceNoWithDifferentAmountOrCounterpartyGetsDifferentStandardNo() {
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> changedAmount = new ArrayList<>(sourceRow);
    changedAmount.set(6, "100.11");
    List<String> changedCounterparty = new ArrayList<>(sourceRow);
    changedCounterparty.set(10, "对手乙");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
        List.of(sourceRow, changedAmount, changedCounterparty)));
    assertEquals(3, result.getRows().size());
    Set<String> standardNos = new HashSet<>();
    for (CsvBankStatementRow row : result.getRows()) standardNos.add(row.getTransactionNo());
    assertEquals(3, standardNos.size());
  }

  @Test
  void fixedTransactionNoAndEveryIdentityFieldChangesIt() {
    String expected = "NBCB1-ac5b30b20f531b30d8f3a631f352f3c0a8be9dab9b80c943109af8f50649997d";
    List<String> sourceRow = sampleDataRows().get(0);
    assertEquals(expected, parse(workbook("Sheet1", List.of(sourceRow))).getRows().get(0).getTransactionNo());

    int[] identityColumns = {0, 2, 3, 6, 8, 9, 10, 11, 13, 14};
    String[] replacements = {
        "SRC-CHANGED", "2026-02-07", "09:00:02", "888.80", "7777.70",
        "CP-CHANGED", "对手变更", "银行变更", "摘要变更", "备注变更"};
    for (int i = 0; i < identityColumns.length; i++) {
      List<String> changed = new ArrayList<>(sourceRow);
      changed.set(identityColumns[i], replacements[i]);
      String changedTransactionNo = parse(workbook("Sheet1", List.of(changed)))
          .getRows().get(0).getTransactionNo();
      assertNotEquals(expected, changedTransactionNo, "身份字段列 " + identityColumns[i] + " 未参与标准号");
    }
  }

  @Test
  void invalidAmountAndMissingDateAreRowErrors() {
    List<String> valid = sampleDataRows().get(0);
    List<String> blankAmount = new ArrayList<>(valid);
    blankAmount.set(6, "");
    blankAmount.set(7, "");
    List<String> malformedAmount = new ArrayList<>(valid);
    malformedAmount.set(6, "not-a-number");
    List<String> missingDate = new ArrayList<>(valid);
    missingDate.set(2, "");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
        List.of(valid, blankAmount, malformedAmount, missingDate)));
    assertEquals(1, result.getRows().size());
    assertEquals(3, result.getErrors().size());
    assertEquals(4, result.getTotalRows());
    assertEquals("amount", result.getErrors().get(0).getField());
    assertEquals("amount", result.getErrors().get(1).getField());
    assertEquals("transaction_date", result.getErrors().get(2).getField());
  }

  @Test
  void incompleteSignatureIsRejected() {
    List<String> incompleteHeader = new ArrayList<>(enterpriseHeader());
    incompleteHeader.remove(5);
    List<String> row = new ArrayList<>(sampleDataRows().get(0));
    row.remove(5);
    assertThrows(BusinessException.class,
        () -> parse(workbook("Sheet1", List.of(row), incompleteHeader)));
  }

  @Test
  void eabAndNbcbSignaturesDoNotCrossRecognize() {
    ExcelBankStatementParser.ExcelParseResult nbcb = parse(workbook("Sheet1", sampleDataRows()));
    assertEquals("宁波银行", nbcb.getTemplates().get(0).get("bank_name"));
    assertTrue(nbcb.getRows().get(0).getTransactionNo().startsWith("NBCB1-"));

    List<String> eabHeader = Arrays.asList("生效日期", "交易日期", "交易时间", "支出金额", "收入金额", "对方账号",
        "对方户名", "对方银行名称", "余额", "成交汇率", "交易流水号", "摘要", "交易备注", "票据凭证号码");
    List<String> eabRow = Arrays.asList("2026-02-06", "2026-02-06", "09:00:01", "100.10", "--",
        "CP-001", "对手甲", "东亚银行上海分行", "899.90", "1.0000", "EAB-SRC-001", "采购付款", "货款", "VP-0001");
    ExcelBankStatementParser.ExcelParseResult eab = parse(workbook("998877665544.csv", List.of(eabRow), eabHeader, 4));
    assertEquals("东亚银行", eab.getTemplates().get(0).get("bank_name"));
    assertTrue(eab.getRows().get(0).getTransactionNo().startsWith("EAB1-"));
  }

  @Test
  void repeatedHeaderAndFooterRowsAreSkipped() {
    List<String> valid = sampleDataRows().get(0);
    List<String> repeatedHeader = enterpriseHeader();
    List<String> footer = new ArrayList<>(Collections.nCopies(15, ""));
    footer.set(2, "合计");
    footer.set(6, "600.10");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
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
    return workbook(sheetName, dataRows, enterpriseHeader(), 3);
  }

  private byte[] workbook(String sheetName, List<List<String>> dataRows, List<String> header) {
    return workbook(sheetName, dataRows, header, 3);
  }

  private byte[] workbook(String sheetName, List<List<String>> dataRows, List<String> header, int preambleRows) {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 1; i <= preambleRows; i++) rows.add(List.of("账户信息说明" + i));
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
    return new ArrayList<>(Arrays.asList("流水号", "交易账号", "交易日期", "交易时间", "币种", "交易网点",
        "借方金额", "贷方金额", "账户余额", "对方账号", "对方户名", "对方开户行", "凭证号", "摘要", "备注"));
  }

  private List<List<String>> sampleDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(nbcbRow("NBCB-SRC-001", "2026-02-06", "09:00:01", "100.10", "", "899.90",
        "CP-001", "对手甲", "宁波银行海曙支行", "采购付款", "货款"));
    rows.add(nbcbRow("NBCB-SRC-002", "2026-02-07", "10:00:02", "", "200.20", "1100.10",
        "CP-002", "对手乙", "宁波银行海曙支行", "销售回款", "回款"));
    rows.add(nbcbRow("NBCB-SRC-003", "2026-02-08", "11:00:03", "300.00", "0.00", "800.10",
        "CP-003", "对手丙", "宁波银行江北支行", "服务费", "服务费"));
    rows.add(nbcbRow("NBCB-SRC-004", "2026-02-09", "12:00:04", "400.00", "", "1200.10",
        "CP-004", "对手丁", "宁波银行江北支行", "原摘要", ""));
    return rows;
  }

  private List<String> nbcbRow(String serialNo, String tradeDate, String tradeTime, String debit,
      String credit, String balance, String cpAccount, String cpName, String cpBank,
      String summary, String remark) {
    return new ArrayList<>(Arrays.asList(serialNo, "900111222333444", tradeDate, tradeTime, "人民币",
        "宁波分行营业部", debit, credit, balance, cpAccount, cpName, cpBank, "--", summary, remark));
  }
}
