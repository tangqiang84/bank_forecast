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

class SuzhouEnterpriseXlsxParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ExcelBankStatementParser parser = new ExcelBankStatementParser(objectMapper);

  @Test
  void recognizesUnnamedSheetAndPreservesEnterpriseFields() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("明细-全部", sampleDataRows()));

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("苏州银行", result.getTemplates().get(0).get("bank_name"));
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
      assertEquals(70, row.getTransactionNo().length());
      assertTrue(row.getTransactionNo().matches("SUZB1-[0-9a-f]{64}"));
      assertEquals("SUZB1|" + row.getTransactionNo(), WeakFingerprint.of(row));
      standardNos.add(row.getTransactionNo());

      JsonNode raw = objectMapper.readTree(row.getRawJson());
      List<String> source = sampleDataRows().get(i);
      assertEquals(row.getTransactionNo(), raw.path("transaction_no").asText());
      assertEquals(source.get(9), raw.path("source_transaction_no").asText());
      assertEquals(source.get(10), raw.path("transaction_time").asText());
      assertEquals(source.get(4), raw.path("counterparty_account").asText());
      assertEquals(source.get(6), raw.path("counterparty_bank").asText());
      assertEquals(source.get(8), raw.path("transaction_purpose").asText());
      sourceNos.add(raw.path("source_transaction_no").asText());
    }
    assertEquals(3, income);
    assertEquals(4, expense);
    assertEquals(7, standardNos.size());
    assertEquals(5, sourceNos.size());
  }

  @Test
  void transactionNosAreStableAcrossParsingAndRowOrder() {
    byte[] workbook = workbook("明细-全部", sampleDataRows());
    List<String> original = transactionNos(parse(workbook));
    List<String> repeated = transactionNos(parse(workbook));
    assertEquals(original, repeated);

    List<List<String>> reversedRows = sampleDataRows();
    Collections.reverse(reversedRows);
    List<String> reversed = transactionNos(parse(workbook("明细-全部", reversedRows)));
    Collections.sort(original);
    Collections.sort(reversed);
    assertEquals(original, reversed);

    List<String> sourceRow = sampleDataRows().get(0);
    ExcelBankStatementParser.ExcelParseResult duplicates = parse(workbook("明细-全部",
        List.of(sourceRow, new ArrayList<>(sourceRow))));
    assertEquals(duplicates.getRows().get(0).getTransactionNo(),
        duplicates.getRows().get(1).getTransactionNo());
  }

  @Test
  void fixedTransactionNoAndEveryIdentityFieldChangesIt() {
    String expected = "SUZB1-7a7f7ad74f4530fb7ca53c2855295427c3f63576696683fc4fd363a86a5548f0";
    List<String> sourceRow = sampleDataRows().get(0);
    assertEquals(expected, parse(workbook("明细-全部", List.of(sourceRow))).getRows().get(0).getTransactionNo());

    String[] replacements = {
        "2026-02-07", "888.80", "777.70", "7777.70", "CP-CHANGED", "对手变更", "银行变更",
        "摘要变更", "用途变更", "SRC-CHANGED", "2026-02-06 23:59:59"};
    for (int column = 0; column < replacements.length; column++) {
      List<String> changed = new ArrayList<>(sourceRow);
      changed.set(column, replacements[column]);
      String changedTransactionNo = parse(workbook("明细-全部", List.of(changed)))
          .getRows().get(0).getTransactionNo();
      assertNotEquals(expected, changedTransactionNo, "身份字段列 " + column + " 未参与标准号");
    }
  }

  @Test
  void canonicalIdentityFieldsProduceSameTransactionNo() {
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> scaledAmount = new ArrayList<>(sourceRow);
    scaledAmount.set(2, "100.1");
    List<String> whitespace = new ArrayList<>(sourceRow);
    whitespace.set(5, "  对手甲  ");
    whitespace.set(10, "2026-02-06   09:00:01");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("明细-全部",
        List.of(sourceRow, scaledAmount, whitespace)));
    assertEquals(3, result.getRows().size());
    String expected = result.getRows().get(0).getTransactionNo();
    assertEquals(expected, result.getRows().get(1).getTransactionNo());
    assertEquals(expected, result.getRows().get(2).getTransactionNo());
  }

  @Test
  void invalidAmountsAreRowErrors() {
    List<String> valid = sampleDataRows().get(0);
    List<String> blankAmount = new ArrayList<>(valid);
    blankAmount.set(2, "");
    blankAmount.set(1, "");
    List<String> zeroAmount = new ArrayList<>(valid);
    zeroAmount.set(2, "0");
    List<String> malformedAmount = new ArrayList<>(valid);
    malformedAmount.set(2, "not-a-number");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("明细-全部",
        List.of(valid, blankAmount, zeroAmount, malformedAmount)));
    assertEquals(1, result.getRows().size());
    assertEquals(3, result.getErrors().size());
    assertEquals(4, result.getTotalRows());
  }

  @Test
  void incompleteOrConflictingSignaturesAreRejected() {
    List<String> genericHeader = List.of("交易日期", "收入", "支出", "流水号");
    List<String> genericRow = List.of("2026-02-06", "10.00", "", "SRC-GENERIC");
    assertThrows(BusinessException.class,
        () -> parse(XlsxWriter.write("明细-全部", List.of(genericHeader, genericRow))));
    assertThrows(BusinessException.class,
        () -> parse(XlsxWriter.write("苏州银行", List.of(genericHeader, genericRow))));
    assertThrows(BusinessException.class,
        () -> parse(XlsxWriter.write("上海银行", List.of(enterpriseHeader(), sampleDataRows().get(0)))));
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
    return Arrays.asList("交易日期", "收入", "支出", "余额", "对方账号", "对方户名",
        "对方行名", "摘要", "用途", "流水号", "机器时间");
  }

  private List<List<String>> sampleDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(Arrays.asList("2026-02-06", "", "100.10", "899.90", "CP-001", "对手甲", "苏州分行",
        "采购付款", "货款", "SUZ-SRC-001", "2026-02-06 09:00:01"));
    rows.add(Arrays.asList("2026-02-07", "200.20", "", "1100.10", "CP-002", "对手乙", "苏州分行",
        "销售回款", "回款", "SUZ-SRC-002", "2026-02-07 10:00:02"));
    rows.add(Arrays.asList("2026-02-08", "", "300.00", "800.10", "CP-003", "对手丙", "园区支行",
        "服务费", "服务费", "SUZ-SRC-003", "2026-02-08 11:00:03"));
    rows.add(Arrays.asList("2026-02-09", "400.00", "", "1200.10", "CP-004", "对手丁", "园区支行",
        "项目回款", "回款", "SUZ-SRC-004", "2026-02-09 12:00:04"));
    rows.add(Arrays.asList("2026-02-10", "", "500.00", "700.10", "CP-005", "对手戊", "新区支行",
        "原摘要", "付款", "SUZ-SRC-005", "2026-02-10 13:00:05"));
    rows.add(Arrays.asList("2026-02-10", "", "500.00", "700.10", "CP-005", "对手戊", "新区支行",
        "原摘要", "付款", "SUZ-SRC-005", "2026-02-10 14:00:06"));
    rows.add(Arrays.asList("2026-02-10", "600.00", "", "1300.10", "CP-005", "对手戊", "新区支行",
        "新摘要", "回款", "SUZ-SRC-005", "2026-02-10 15:00:07"));
    return rows;
  }
}
