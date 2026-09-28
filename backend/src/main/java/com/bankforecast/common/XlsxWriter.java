package com.bankforecast.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 生成最小 xlsx 工作簿（单工作表、行内字符串），不引入第三方依赖。 */
public final class XlsxWriter {

  private XlsxWriter() {
  }

  public static byte[] write(String sheetName, List<List<String>> rows) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
      put(zip, "[Content_Types].xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
              + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
              + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
              + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
              + "</Types>");
      put(zip, "_rels/.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
              + "</Relationships>");
      put(zip, "xl/workbook.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
              + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
              + "<sheets><sheet name=\"" + escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      put(zip, "xl/_rels/workbook.xml.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
              + "</Relationships>");
      put(zip, "xl/worksheets/sheet1.xml", sheetXml(rows));
      zip.finish();
      zip.close();
      return out.toByteArray();
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Excel 导出失败，请稍后重试");
    }
  }

  private static String sheetXml(List<List<String>> rows) {
    StringBuilder xml = new StringBuilder(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    for (int r = 0; r < rows.size(); r++) {
      xml.append("<row r=\"").append(r + 1).append("\">");
      List<String> cells = rows.get(r);
      for (int c = 0; c < cells.size(); c++) {
        String ref = columnName(c) + (r + 1);
        String value = cells.get(c);
        if (r > 0 && isNumber(value)) {
          xml.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
        } else {
          xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(escape(value)).append("</t></is></c>");
        }
      }
      xml.append("</row>");
    }
    xml.append("</sheetData></worksheet>");
    return xml.toString();
  }

  private static boolean isNumber(String value) {
    return value != null && value.matches("-?\\d+(\\.\\d+)?");
  }

  private static String columnName(int index) {
    StringBuilder name = new StringBuilder();
    int current = index;
    while (true) {
      name.insert(0, (char) ('A' + current % 26));
      current = current / 26 - 1;
      if (current < 0) return name.toString();
    }
  }

  private static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static void put(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
