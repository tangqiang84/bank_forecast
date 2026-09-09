package com.bankforecast.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestClientConfig {

  @Bean
  public RestTemplate analyticsRestTemplate(
      RestTemplateBuilder builder,
      @Value("${bank-forecast.analytics.connect-timeout-ms:2000}") int connectTimeout,
      @Value("${bank-forecast.analytics.read-timeout-ms:10000}") int readTimeout) {
    return builder
        .setConnectTimeout(Duration.ofMillis(connectTimeout))
        .setReadTimeout(Duration.ofMillis(readTimeout))
        .build();
  }
}
