package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankforecast.contract.CsvContractParser;
import com.bankforecast.contract.CsvContractRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class CsvParserTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesBankStatementWithInOutAndBom() {
    CsvBankStatementParser parser = new CsvBankStatementParser(objectMapper);
    String csv = "\uFEFF交易流水号,交易日期,收支方向,交易金额\nTX-1,2026/09/09,in,\"1,234.50\"\n";

    CsvParseResult<CsvBankStatementRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("income", result.getRows().get(0).getDirection());
    assertEquals("1234.50", result.getRows().get(0).getAmount().toPlainString());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void parsesContractWithChineseHeadersAndGb18030() {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "合同编号,合同名称,客户名称,合同金额,节点名称,节点类型,到期日期,应收金额\n"
        + "HT-1,软件合同,甲方公司,\"10,000.00\",验收款,acceptance,20260909,\"10,000.00\"\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(Charset.forName("GB18030"))), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("软件合同", result.getRows().get(0).getContractName());
    assertEquals("2026-09-09", result.getRows().get(0).getDueDate().toString());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void acceptsRequiredMarkerInContractHeader() {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "合同编号,合同名称（必填）,客户名称,合同金额,节点名称,节点类型,到期日期,应收金额\n"
        + "HT-2,软件合同,甲方公司,100.00,验收款,acceptance,2026-09-09,100.00\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void parsesSemicolonSeparatedUtf16ContractFile() {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "contract_no;合同名称（文本）;customer_name;contract_amount;node_name;node_type;due_date;plan_amount\n"
        + "HT-3;跨境合同;客户;100.00;验收款;acceptance;2026-09-09;100.00\n";
    byte[] content = ("\uFEFF" + csv).getBytes(Charset.forName("UTF-16LE"));

    CsvParseResult<CsvContractRow> result = parser.parse(new ByteArrayInputStream(content), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("跨境合同", result.getRows().get(0).getContractName());
  }

  @Test
  void parsesContractMasterSampleWithoutContractNameOrReceivableColumns() throws Exception {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "contract_no,customer_name,project_no,project_name,contract_amount,sign_date,status\n"
        + "XC-2026-001,瑞丰金融集团,PRJ-001,核心系统上云迁移,1200000.00,2026-05-10,执行中\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("核心系统上云迁移", result.getRows().get(0).getContractName());
    assertEquals(null, result.getRows().get(0).getNodeName());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void parsesReceivableSampleWithoutContractAmountAndNodeType() throws Exception {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "plan_id,contract_no,customer_name,project_no,node_name,due_date,plan_amount,received_amount,status\n"
        + "RP-001,XC-2026-001,瑞丰金融集团,PRJ-001,验收款,2026-08-15,400000.00,0.00,逾期未收\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertEquals(null, result.getRows().get(0).getContractAmount());
    assertEquals("receivable", result.getRows().get(0).getNodeType());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void recognizesSixBankSheetsAndMapsDebitCreditColumns() throws Exception {
    String sample = "../../docs/sample/六家银行企业网银交易明细流水格式与样本.xlsx";
    ExcelBankStatementParser.ExcelParseResult result = new ExcelBankStatementParser(objectMapper)
        .parse(Files.newInputStream(Paths.get(sample)), 100);

    assertEquals(6, result.getTemplates().size());
    assertEquals(30, result.getRows().size());
    assertEquals("expense", result.getRows().get(0).getDirection());
    assertEquals("150000", result.getRows().get(0).getAmount().toPlainString());
    assertTrue(result.getErrors().isEmpty());
  }
}
