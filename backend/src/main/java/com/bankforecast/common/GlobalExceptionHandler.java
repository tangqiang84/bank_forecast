package com.bankforecast.common;

import java.util.ArrayList;
import javax.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
    return ResponseEntity.badRequest().body(ApiResponse.fail(40001, "参数校验失败", data));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraint(ConstraintViolationException ex) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("error", "参数校验失败");
    data.put("details", ex.getMessage());
    return ResponseEntity.badRequest().body(ApiResponse.fail(40001, "参数校验失败", data));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleException(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(50000, "系统异常"));
  }
}
