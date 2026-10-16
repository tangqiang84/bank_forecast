package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.XlsxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoscEnterpriseXlsxParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ExcelBankStatementParser parser = new ExcelBankStatementParser(objectMapper);

  @Test
  void recognizesTestReportAndPreservesEnterpriseFields() throws Exception {
    List<List<String>> dataRows = sampleDataRows();

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("testReport", dataRows));

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("上海银行", result.getTemplates().get(0).get("bank_name"));
    assertEquals(6, result.getTotalRows());
    assertEquals(6, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    int income = 0;
    int expense = 0;
    Set<String> transactionNos = new HashSet<>();
    Set<String> sourceTransactionNos = new HashSet<>();
    for (int i = 0; i < result.getRows().size(); i++) {
      CsvBankStatementRow row = result.getRows().get(i);
      if ("income".equals(row.getDirection())) income++;
      if ("expense".equals(row.getDirection())) expense++;
      assertFalse(row.getCounterpartyName() == null || row.getCounterpartyName().isBlank());
      assertFalse(row.getSummary() == null || row.getSummary().isBlank());
      assertEquals(70, row.getTransactionNo().length());
      assertTrue(row.getTransactionNo().matches("BOSC1-[0-9a-f]{64}"));
      transactionNos.add(row.getTransactionNo());

      JsonNode raw = objectMapper.readTree(row.getRawJson());
      List<String> source = dataRows.get(i);
      assertEquals(row.getTransactionNo(), raw.path("transaction_no").asText());
      assertEquals(source.get(1), raw.path("source_transaction_no").asText());
      assertEquals(source.get(2), raw.path("transaction_time").asText());
      assertEquals(source.get(7), raw.path("counterparty_account").asText());
      assertEquals(source.get(10), raw.path("transaction_purpose").asText());
      assertEquals(source.get(11), raw.path("remark").asText());
      sourceTransactionNos.add(raw.path("source_transaction_no").asText());
    }
    assertEquals(3, income);
    assertEquals(3, expense);
    assertEquals(6, transactionNos.size());
    assertEquals(5, sourceTransactionNos.size());
    assertNotEquals(result.getRows().get(4).getTransactionNo(), result.getRows().get(5).getTransactionNo());
    assertEquals("项目回款", result.getRows().get(1).getSummary());
    assertEquals("备注摘要", result.getRows().get(2).getSummary());
  }

  @Test
  void transactionNosAreStableAcrossParsingAndRowOrder() {
    byte[] workbook = workbook("testReport", sampleDataRows());
    List<String> original = transactionNos(parse(workbook));
    List<String> repeated = transactionNos(parse(workbook));
    assertEquals(original, repeated);

    List<List<String>> reversedRows = sampleDataRows();
    Collections.reverse(reversedRows);
    List<String> reversed = transactionNos(parse(workbook("testReport", reversedRows)));
    Collections.sort(original);
    Collections.sort(reversed);
    assertEquals(original, reversed);
  }

  @Test
  void identicalRowsHaveSameTransactionNoAndEveryIdentityFieldChangesIt() {
    List<String> sourceRow = sampleDataRows().get(0);
    ExcelBankStatementParser.ExcelParseResult duplicates = parse(workbook("testReport",
        List.of(sourceRow, new ArrayList<>(sourceRow))));
    assertEquals(2, duplicates.getRows().size());
    assertEquals(duplicates.getRows().get(0).getTransactionNo(),
        duplicates.getRows().get(1).getTransactionNo());

    String original = duplicates.getRows().get(0).getTransactionNo();
    assertEquals("BOSC1|" + original, WeakFingerprint.of(duplicates.getRows().get(0)));
    assertEquals(WeakFingerprint.of(duplicates.getRows().get(0)),
        WeakFingerprint.of(duplicates.getRows().get(1)));
    List<String> differentTime = new ArrayList<>(sourceRow);
    differentTime.set(2, "2026-09-10 23:59:59");
    CsvBankStatementRow changedTimeRow = parse(workbook("testReport", List.of(differentTime)))
        .getRows().get(0);
    assertNotEquals(WeakFingerprint.of(duplicates.getRows().get(0)), WeakFingerprint.of(changedTimeRow));

    String[] replacements = {
        null, "SRC-CHANGED", "2026-09-10 23:59:59", "2026-09-11", "入账(贷方)",
        "888.80", "7777.70", "CP-CHANGED", "对手变更", "摘要变更", "用途变更", "备注重改"};
    for (int column = 1; column <= 11; column++) {
      List<String> changed = new ArrayList<>(sourceRow);
      changed.set(column, replacements[column]);
      String changedTransactionNo = parse(workbook("testReport", List.of(changed)))
          .getRows().get(0).getTransactionNo();
      assertNotEquals(original, changedTransactionNo, "身份字段列 " + column + " 未参与标准号");
    }
  }

  @Test
  void invalidDirectionsAndAmountsAreRowErrors() {
    List<String> valid = sampleDataRows().get(0);
    List<String> invalidDirection = new ArrayList<>(valid);
    invalidDirection.set(4, "收入");
    List<String> blankAmount = new ArrayList<>(valid);
    blankAmount.set(5, "");
    List<String> zeroAmount = new ArrayList<>(valid);
    zeroAmount.set(5, "0");
    List<String> negativeAmount = new ArrayList<>(valid);
    negativeAmount.set(5, "-1.00");
    List<String> malformedAmount = new ArrayList<>(valid);
    malformedAmount.set(5, "not-a-number");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("testReport",
        List.of(valid, invalidDirection, blankAmount, zeroAmount, negativeAmount, malformedAmount)));

    assertEquals(1, result.getRows().size());
    assertEquals(5, result.getErrors().size());
    assertEquals(6, result.getTotalRows());
    assertEquals("transaction_direction", result.getErrors().get(0).getField());
    assertEquals(8, result.getErrors().get(0).getRowNo());
    assertEquals(12, result.getErrors().get(4).getRowNo());
    assertTrue(result.getRows().get(0).getAmount().signum() > 0);
  }

  @Test
  void fixedTransactionNoAndCanonicalizesIdentityFields() {
    String expected = "BOSC1-363689feabb75d723cc6983b0daccdba9be8339545a2a759b7d252cfccb76667";
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> scaledAmount = new ArrayList<>(sourceRow);
    scaledAmount.set(5, "100.1");
    List<String> slashDate = new ArrayList<>(sourceRow);
    slashDate.set(3, "2026/09/10");
    List<String> whitespace = new ArrayList<>(sourceRow);
    whitespace.set(1, "  SRC-001  ");
    whitespace.set(2, "2026-09-10   09:00:01");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("testReport",
        List.of(sourceRow, scaledAmount, slashDate, whitespace)));

    assertEquals(4, result.getRows().size());
    for (CsvBankStatementRow row : result.getRows()) assertEquals(expected, row.getTransactionNo());
  }

  @Test
  void legacyBoscDualAmountRejectsNonPositiveCreditsAsRowErrors() {
    List<String> header = List.of(
        "交易流水号", "记账日期", "借方金额", "贷方金额", "对手名称", "摘要");
    List<String> valid = List.of("DUAL-OK", "2026-09-10", "", "10.00", "对手甲", "正常");
    List<String> zero = List.of("DUAL-ZERO", "2026-09-10", "", "0", "对手乙", "零金额");
    List<String> negative = List.of("DUAL-NEG", "2026-09-10", "", "-1.00", "对手丙", "负金额");

    ExcelBankStatementParser.ExcelParseResult result = parse(
        XlsxWriter.write("上海银行旧双金额", List.of(header, valid, zero, negative)));

    assertEquals(1, result.getRows().size());
    assertEquals("DUAL-OK", result.getRows().get(0).getTransactionNo());
    assertEquals(2, result.getErrors().size());
    assertEquals(3, result.getErrors().get(0).getRowNo());
    assertEquals(4, result.getErrors().get(1).getRowNo());
    assertEquals(3, result.getTotalRows());
  }

  @Test
  void incompleteSignaturesAreRejectedAndDoNotEnableBankInference() {
    List<String> genericHeader = List.of("", "交易流水号", "记账日期", "交易方向", "交易金额");
    List<String> genericRow = List.of("", "SRC-GENERIC", "2026-09-10", "入账(贷方)", "10.00");
    byte[] generic = XlsxWriter.write("testReport", List.of(genericHeader, genericRow));
    assertThrows(BusinessException.class, () -> parse(generic));

    byte[] named = XlsxWriter.write("上海银行旧格式", List.of(genericHeader, genericRow));
    assertThrows(BusinessException.class, () -> parse(named));

    List<String> almostCompleteHeader = new ArrayList<>(enterpriseHeader());
    almostCompleteHeader.remove(11);
    List<String> almostCompleteRow = new ArrayList<>(sampleDataRows().get(0));
    almostCompleteRow.remove(11);
    byte[] almostComplete = XlsxWriter.write("testReport",
        List.of(almostCompleteHeader, almostCompleteRow));
    assertThrows(BusinessException.class, () -> parse(almostComplete));
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
    List<List<String>> rows = new ArrayList<>();
    for (int i = 1; i <= 5; i++) rows.add(List.of("企业网银交易明细说明" + i));
    rows.add(enterpriseHeader());
    rows.addAll(dataRows);
    return XlsxWriter.write(sheetName, rows);
  }

  private List<String> enterpriseHeader() {
    return row("交易流水号", "交易时间", "记账日期", "交易方向", "交易金额", "余额",
        "对手账号", "对手名称", "摘要", "交易用途", "备注");
  }

  private List<List<String>> sampleDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(row("SRC-001", "2026-09-10 09:00:01", "2026-09-10", "出账(借方)", "100.10", "899.90",
        "CP-001", "对手甲", "采购付款", "货款", "备注一"));
    rows.add(row("SRC-002", "2026-09-10 10:00:02", "2026-09-10", "入账(贷方)", "200.20", "1100.10",
        "CP-002", "对手乙", "", "项目回款", "备注二"));
    rows.add(row("SRC-003", "2026-09-10 11:00:03", "2026-09-10", "出账(借方)", "300.00", "800.10",
        "CP-003", "对手丙", "", "", "备注摘要"));
    rows.add(row("SRC-004", "2026-09-10 12:00:04", "2026-09-10", "入账(贷方)", "400.00", "1200.10",
        "CP-004", "对手丁", "服务费", "服务费", "备注四"));
    rows.add(row("SRC-005", "2026-09-10 13:00:05", "2026-09-10", "出账(借方)", "500.00", "700.10",
        "CP-005", "对手戊", "原摘要", "付款", "备注五"));
    rows.add(row("SRC-005", "2026-09-10 15:00:06", "2026-09-10", "入账(贷方)", "600.00", "1300.10",
        "CP-005", "对手戊", "新摘要", "回款", "备注六"));
    return rows;
  }

  private List<String> row(String... values) {
    List<String> row = new ArrayList<>();
    row.add("");
    row.addAll(Arrays.asList(values));
    return row;
  }
}
