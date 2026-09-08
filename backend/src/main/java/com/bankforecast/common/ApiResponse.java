package com.bankforecast.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final String traceId;

  public ApiResponse(int code, String message, T data, String traceId) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.traceId = traceId;
  }

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data, TraceIdHolder.next());
  }

  public static <T> ApiResponse<T> fail(int code, String message) {
    return new ApiResponse<>(code, message, null, TraceIdHolder.next());
  }

  public static <T> ApiResponse<T> fail(int code, String message, T data) {
    return new ApiResponse<>(code, message, data, TraceIdHolder.next());
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }

  @JsonProperty("trace_id")
  public String getTraceId() {
    return traceId;
  }
}
