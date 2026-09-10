package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankforecast.contract.CsvContractParser;
import com.bankforecast.contract.CsvContractRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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
    XSSFWorkbook workbook = new XSSFWorkbook();
    String[] banks = {"中国银行", "工商银行", "广发银行", "平安银行", "上海银行", "苏州银行"};
    for (String bank : banks) {
      org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(bank);
      sheet.createRow(0).createCell(0).setCellValue(bank + "交易明细");
      Row header = sheet.createRow(1);
      String[] headers = {"交易日期", "摘要", "借方金额", "贷方金额", "余额", "对方户名", "凭证号"};
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      Row data = sheet.createRow(2);
      data.createCell(0).setCellValue("2026-09-10");
      data.createCell(1).setCellValue("收款");
      data.createCell(3).setCellValue(100.00);
      data.createCell(4).setCellValue(1000.00);
      data.createCell(5).setCellValue("示例客户");
      data.createCell(6).setCellValue(bank + "-TX-001");
      sheet.createRow(3).createCell(0).setCellValue("备注：以上为样本数据");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    workbook.write(output);
    workbook.close();

    ExcelBankStatementParser.ExcelParseResult result = new ExcelBankStatementParser(objectMapper)
        .parse(new ByteArrayInputStream(output.toByteArray()), 100);

    assertEquals(6, result.getTemplates().size());
    assertEquals(6, result.getRows().size());
    assertEquals("income", result.getRows().get(0).getDirection());
    assertEquals("100.00", result.getRows().get(0).getAmount().toPlainString());
    assertTrue(result.getErrors().isEmpty());
  }
}
