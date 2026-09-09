package com.bankforecast.common;

import java.util.ArrayList;
import javax.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("error", "参数校验失败");
    ArrayList<Map<String, Object>> details = new ArrayList<Map<String, Object>>();
    ex.getBindingResult().getFieldErrors().forEach(error -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("field", error.getField());
      item.put("message", error.getDefaultMessage());
      details.add(item);
    });
    data.put("details", details);
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR, "参数校验失败", data));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraint(ConstraintViolationException ex) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("error", "参数校验失败");
    data.put("details", ex.getMessage());
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR, "参数校验失败", data));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    String traceId = TraceIdHolder.next();
    log.warn("请求媒体类型不支持, traceId={}, contentType={}, supported={}", traceId,
        ex.getContentType() == null ? "unknown" : ex.getContentType().toString(), ex.getSupportedMediaTypes());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("trace_id", traceId);
    data.put("supported_content_type", MediaType.APPLICATION_JSON_VALUE);
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR,
        "请求格式不正确，请使用 JSON 请求体", data));
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex) {
    HttpStatus status = ex.getCode() >= 50000 ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage(), ex.getData()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleException(Exception ex) {
    String traceId = TraceIdHolder.next();
    log.error("系统异常, traceId={}", traceId, ex);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("trace_id", traceId);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试", data));
  }
}
