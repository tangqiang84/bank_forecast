package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class CmbEnterpriseXlsxParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ExcelBankStatementParser parser = new ExcelBankStatementParser(objectMapper);

  @Test
  void recognizesCmbSignatureRegardlessOfSheetNameAndMapsFields() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1", sampleDataRows()));

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("招商银行", result.getTemplates().get(0).get("bank_name"));
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
      assertTrue(row.getTransactionNo().matches("CMB1-[0-9a-f]{64}"));
      assertEquals("CMB1|" + row.getTransactionNo(), WeakFingerprint.of(row));
      standardNos.add(row.getTransactionNo());

      JsonNode raw = objectMapper.readTree(row.getRawJson());
      List<String> source = sampleDataRows().get(i);
      assertEquals(row.getTransactionNo(), raw.path("transaction_no").asText());
      assertEquals(source.get(11), raw.path("source_transaction_no").asText());
      assertEquals(source.get(4), raw.path("transaction_time").asText());
      assertEquals(source.get(3), raw.path("transaction_date").asText());
      assertEquals(source.get(5), raw.path("value_date").asText());
      assertEquals(source.get(6), raw.path("transaction_type").asText());
      assertEquals(source.get(20), raw.path("counterparty_account").asText());
      assertEquals(source.get(19), raw.path("counterparty_name").asText());
      assertEquals(source.get(22), raw.path("counterparty_bank").asText());
      assertEquals(source.get(10), raw.path("summary").asText());
      assertEquals(source.get(14), raw.path("transaction_purpose").asText());
      assertEquals(source.get(2), raw.path("币种").asText());
      assertEquals("招商银行", raw.path("source_bank").asText());
      assertEquals("Sheet1", raw.path("source_sheet").asText());
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
  }

  @Test
  void transactionDateComesFromTradeDateColumnNotValueDate() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1", sampleDataRows()));
    CsvBankStatementRow first = result.getRows().get(0);
    assertEquals("2026-02-06", first.getTransactionDate().toString());
    JsonNode raw = objectMapper.readTree(first.getRawJson());
    assertEquals("2026-02-06", raw.path("transaction_date").asText());
    assertEquals("2026-02-09", raw.path("value_date").asText());
  }

  @Test
  void emptyCounterpartyRowImportsNormally() throws Exception {
    List<String> payroll = cmbRow("2026-02-11", "16:00:08", "2026-02-11", "代发工资", "88.00", "",
        "612.10", "代发工资", "CMB-SRC-006", "工资", "", "", "");
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1", List.of(payroll)));
    assertEquals(1, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    CsvBankStatementRow row = result.getRows().get(0);
    assertEquals("expense", row.getDirection());
    assertTrue(row.getCounterpartyName() == null || row.getCounterpartyName().isEmpty());
    assertTrue(row.getTransactionNo().matches("CMB1-[0-9a-f]{64}"));
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
    changedAmount.set(7, "100.11");
    List<String> changedCounterparty = new ArrayList<>(sourceRow);
    changedCounterparty.set(19, "对手乙");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
        List.of(sourceRow, changedAmount, changedCounterparty)));
    assertEquals(3, result.getRows().size());
    Set<String> standardNos = new HashSet<>();
    for (CsvBankStatementRow row : result.getRows()) standardNos.add(row.getTransactionNo());
    assertEquals(3, standardNos.size());
  }

  @Test
  void fixedTransactionNoAndEveryIdentityFieldChangesIt() {
    String expected = "CMB1-850e70ae64b107376e55da2333e4deb3d00f02d0f01e4c0f782c185b95f6d0d0";
    List<String> sourceRow = sampleDataRows().get(1);
    assertEquals(expected, parse(workbook("Sheet1", List.of(sourceRow))).getRows().get(0).getTransactionNo());

    int[] identityColumns = {3, 4, 8, 9, 10, 11, 14, 19, 20, 22};
    String[] replacements = {
        "2026-02-08", "10:00:09", "888.80", "7777.70", "摘要变更", "SRC-CHANGED",
        "用途变更", "对手变更", "CP-CHANGED", "银行变更"};
    for (int i = 0; i < identityColumns.length; i++) {
      List<String> changed = new ArrayList<>(sourceRow);
      changed.set(identityColumns[i], replacements[i]);
      String changedTransactionNo = parse(workbook("Sheet1", List.of(changed)))
          .getRows().get(0).getTransactionNo();
      assertNotEquals(expected, changedTransactionNo, "身份字段列 " + identityColumns[i] + " 未参与标准号");
    }
  }

  @Test
  void canonicalIdentityFieldsProduceSameTransactionNo() {
    List<String> sourceRow = sampleDataRows().get(0);
    List<String> scaledAmount = new ArrayList<>(sourceRow);
    scaledAmount.set(7, "100.1");
    List<String> whitespace = new ArrayList<>(sourceRow);
    whitespace.set(19, "  对手甲  ");
    whitespace.set(4, "09:00:01  ");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
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
    blankAmount.set(7, "");
    blankAmount.set(8, "");
    List<String> zeroAmount = new ArrayList<>(valid);
    zeroAmount.set(7, "0");
    List<String> malformedAmount = new ArrayList<>(valid);
    malformedAmount.set(7, "not-a-number");
    List<String> missingDate = new ArrayList<>(valid);
    missingDate.set(3, "");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
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
    incompleteHeader.remove(6);
    List<String> row = new ArrayList<>(sampleDataRows().get(0));
    row.remove(6);
    assertThrows(BusinessException.class,
        () -> parse(workbook("Sheet1", List.of(row), incompleteHeader)));

    List<String> genericHeader = List.of("交易日期", "收入", "支出", "流水号");
    List<String> genericRow = List.of("2026-02-06", "10.00", "", "SRC-GENERIC");
    assertThrows(BusinessException.class,
        () -> parse(workbook("Sheet1", List.of(genericRow), genericHeader)));
  }

  @Test
  void repeatedHeaderAndFooterRowsAreSkipped() {
    List<String> valid = sampleDataRows().get(0);
    List<String> repeatedHeader = enterpriseHeader();
    List<String> footer = new ArrayList<>(Collections.nCopies(36, ""));
    footer.set(3, "合计");
    footer.set(7, "600.10");

    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1",
        List.of(valid, repeatedHeader, footer)));
    assertEquals(1, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals(3, result.getTotalRows());
  }

  @Test
  void emptyCounterpartyCellsAreNullInRawJson() throws Exception {
    List<String> payroll = cmbRow("2026-02-11", "16:00:08", "2026-02-11", "代发工资", "88.00", "",
        "612.10", "代发工资", "CMB-SRC-006", "工资", null, null, null);
    ExcelBankStatementParser.ExcelParseResult result = parse(workbook("Sheet1", List.of(payroll)));
    assertEquals(1, result.getRows().size());
    CsvBankStatementRow row = result.getRows().get(0);
    assertNull(row.getCounterpartyName());
    JsonNode raw = objectMapper.readTree(row.getRawJson());
    assertTrue(raw.path("counterparty_name").isNull());
    assertTrue(raw.path("counterparty_account").isNull());
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
    for (int i = 1; i <= 7; i++) rows.add(List.of("企业网银元数据说明" + i));
    rows.add(List.of(""));
    rows.add(header);
    rows.addAll(dataRows);
    return XlsxWriter.write(sheetName, rows);
  }

  private List<String> enterpriseHeader() {
    return new ArrayList<>(Arrays.asList("账号", "账号名称", "币种", "交易日", "交易时间", "起息日", "交易类型",
        "借方金额", "贷方金额", "余额", "摘要", "流水号", "流程实例号", "业务名称", "用途", "业务参考号", "业务摘要",
        "其它摘要", "收(付)方分行名", "收(付)方名称", "收(付)方账号", "收(付)方开户行行号", "收(付)方开户行名",
        "收(付)方开户行地址", "母(子)公司账号分行名", "母(子)公司账号", "母(子)公司名称", "信息标志", "有否附件信息",
        "冲账标志", "扩展摘要", "交易分析码", "票据号", "商务支付订单号", "内部编号", "公司一卡通号"));
  }

  private List<List<String>> sampleDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(cmbRow("2026-02-06", "09:00:01", "2026-02-09", "支付", "100.10", "", "899.90",
        "采购付款", "CMB-SRC-001", "货款", "对手甲", "CP-001", "招商银行深圳分行"));
    rows.add(cmbRow("2026-02-07", "10:00:02", "2026-02-07", "收款", "", "200.20", "1100.10",
        "销售回款", "CMB-SRC-002", "回款", "对手乙", "CP-002", "招商银行深圳分行"));
    rows.add(cmbRow("2026-02-08", "11:00:03", "2026-02-08", "支付", "300.00", "", "800.10",
        "服务费", "CMB-SRC-003", "服务费", "对手丙", "CP-003", "招商银行广州分行"));
    rows.add(cmbRow("2026-02-09", "12:00:04", "2026-02-09", "收款", "", "400.00", "1200.10",
        "项目回款", "CMB-SRC-004", "回款", "对手丁", "CP-004", "招商银行广州分行"));
    rows.add(cmbRow("2026-02-10", "13:00:05", "2026-02-10", "支付", "500.00", "", "700.10",
        "原摘要", "CMB-SRC-005", "付款", "对手戊", "CP-005", "招商银行北京分行"));
    rows.add(cmbRow("2026-02-10", "14:00:06", "2026-02-10", "支付", "500.00", "", "700.10",
        "原摘要", "CMB-SRC-005", "付款", "对手戊", "CP-005", "招商银行北京分行"));
    rows.add(cmbRow("2026-02-10", "15:00:07", "2026-02-10", "收款", "", "600.00", "1300.10",
        "新摘要", "CMB-SRC-005", "回款", "对手戊", "CP-005", "招商银行北京分行"));
    return rows;
  }

  private List<String> cmbRow(String tradeDate, String tradeTime, String valueDate, String type,
      String debit, String credit, String balance, String summary, String serialNo, String purpose,
      String cpName, String cpAccount, String cpBank) {
    List<String> row = new ArrayList<>(Arrays.asList("755999888777666", "深圳分公司人民币户", "人民币",
        tradeDate, tradeTime, valueDate, type, debit, credit, balance, summary, serialNo,
        "PI-0001", "企业支付", purpose, "BR-0001", "业务摘要一", "其它摘要一", "深圳分行"));
    if (cpName == null && cpAccount == null && cpBank == null) return row;
    row.add(cpName);
    row.add(cpAccount);
    row.addAll(Arrays.asList("308584000013", cpBank, "深圳市南山区", "总行营业部", "755000000000001",
        "母公司的名字", "0", "无", "0", "扩展摘要一", "A001", "票据一", "订单一", "内部一", "一卡通一"));
    return row;
  }
}
