package com.bankforecast.api;

import com.bankforecast.auth.AuthService;
import com.bankforecast.auth.LoginRequest;
import com.bankforecast.common.ApiResponse;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
    return ApiResponse.ok(authService.login(request));
  }

  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me() {
    return ApiResponse.ok(authService.currentUser());
  }
}
