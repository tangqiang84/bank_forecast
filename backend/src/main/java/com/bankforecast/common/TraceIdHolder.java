package com.bankforecast.common;

import java.util.UUID;

public final class TraceIdHolder {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private TraceIdHolder() {}

  /** 当前请求的 trace_id；非请求线程（无上下文）时生成新值并保持到显式 clear。 */
  public static String current() {
    String value = CURRENT.get();
    if (value == null) {
      value = next();
      CURRENT.set(value);
    }
    return value;
  }

  public static void set(String traceId) {
    CURRENT.set(traceId);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static String next() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
