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

public final class CsvImportSupport {
  private CsvImportSupport() {}

  public static String readText(InputStream inputStream) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int length;
    while ((length = inputStream.read(buffer)) >= 0) output.write(buffer, 0, length);
    byte[] bytes = output.toByteArray();
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
        .trim()
        .toLowerCase()
        .replaceAll("\\s+", "");
  }

  public static String normalizeAmount(String value) {
    return value.trim().replace(",", "").replace("￥", "").replace("¥", "");
  }

  private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
    CharBuffer chars = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes));
    return chars.toString();
  }
}
