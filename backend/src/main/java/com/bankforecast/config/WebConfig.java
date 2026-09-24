package com.bankforecast.config;

import com.bankforecast.security.AuthInterceptor;
import com.bankforecast.security.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Value("${bank-forecast.cors.allowed-origins:http://127.0.0.1:5173,http://127.0.0.1:5174,http://127.0.0.1:5175,http://localhost:5173,http://localhost:5174,http://localhost:5175}")
  private String allowedOrigins;

  private final AuthInterceptor authInterceptor;
  private final PermissionInterceptor permissionInterceptor;

  public WebConfig(AuthInterceptor authInterceptor, PermissionInterceptor permissionInterceptor) {
    this.authInterceptor = authInterceptor;
    this.permissionInterceptor = permissionInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String[] origins = allowedOrigins.split(",");
    registry.addMapping("/api/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor).addPathPatterns("/api/v1/**").order(1);
    registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/v1/**").order(2);
  }
}
