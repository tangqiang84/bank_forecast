package com.bankforecast.importjob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class CsvImportSupport {
  private CsvImportSupport() {}

  public static String readText(InputStream inputStream) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int length;
    while ((length = inputStream.read(buffer)) >= 0) output.write(buffer, 0, length);
    byte[] bytes = output.toByteArray();
    if (startsWith(bytes, new byte[] {(byte) 0xFF, (byte) 0xFE})) {
      return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16LE"));
    }
    if (startsWith(bytes, new byte[] {(byte) 0xFE, (byte) 0xFF})) {
      return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16BE"));
    }
    try {
      return decode(bytes, StandardCharsets.UTF_8);
    } catch (CharacterCodingException ex) {
      return decode(bytes, Charset.forName("GB18030"));
    }
  }

  public static String normalizeHeader(String value) {
    if (value == null) return "";
    return value.replace("\uFEFF", "")
        .replace("\u200B", "")
        .replace('\u3000', ' ')
        .replace('＿', '_')
        .replaceAll("[（(][^）)]*[）)]", "")
        .replace("*", "")
        .trim()
        .toLowerCase()
        .replaceAll("\\s+", "");
  }

  public static char detectDelimiter(String headerLine) {
    int comma = count(headerLine, ',');
    int semicolon = count(headerLine, ';');
    int tab = count(headerLine, '\t');
    if (semicolon > comma && semicolon >= tab) return ';';
    if (tab > comma && tab > semicolon) return '\t';
    return ',';
  }

  public static String normalizeAmount(String value) {
    return value.trim().replace(",", "").replace("￥", "").replace("¥", "");
  }

  private static final DateTimeFormatter[] DATE_FORMATS = {
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("yyyy/M/d"),
      DateTimeFormatter.ofPattern("yyyyMMdd"),
      DateTimeFormatter.ofPattern("yyyy.M.d"),
      DateTimeFormatter.ofPattern("yyyy年M月d日")};
  private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
      DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
      DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")};
  private static final LocalDate EXCEL_SERIAL_EPOCH = LocalDate.of(1899, 12, 30);

  /** 兼容银行导出常见日期：ISO、斜杠、紧凑、点分、中文、日期时间和 Excel 序列值。 */
  public static LocalDate parseFlexibleDate(String value) {
    String text = value == null ? "" : value.trim();
    for (DateTimeFormatter formatter : DATE_FORMATS) {
      try {
        return LocalDate.parse(text, formatter);
      } catch (DateTimeParseException ignored) {
        // 尝试下一种银行导出日期格式
      }
    }
    for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
      try {
        return LocalDateTime.parse(text, formatter).toLocalDate();
      } catch (DateTimeParseException ignored) {
        // 尝试下一种银行导出日期时间格式
      }
    }
    if (text.matches("\\d{5}")) {
      int serial = Integer.parseInt(text);
      if (serial >= 20000 && serial <= 60000) return EXCEL_SERIAL_EPOCH.plusDays(serial);
    }
    throw new DateTimeParseException("unsupported date format", text, 0);
  }

  private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
    CharBuffer chars = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes));
    return chars.toString();
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
    return true;
  }

  private static int count(String value, char target) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) == target) count++;
    return count;
  }
}
