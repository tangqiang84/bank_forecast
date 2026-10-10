package com.bankforecast.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型适配层（P3-9）：OpenAI 兼容 /chat/completions 协议。
 * 通过 LLM_ENABLED/LLM_BASE_URL/LLM_API_KEY/LLM_MODEL 环境变量配置；
 * 未配置或调用失败时返回 empty，调用方降级为规则实现。
 * 可切换私有化模型（同协议）而不改业务代码。
 */
@Component
public class LlmClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmClient.class);

  private final boolean enabled;
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final RestTemplate restTemplate;

  public LlmClient(
      @Value("${bank-forecast.llm.enabled:false}") boolean enabled,
      @Value("${bank-forecast.llm.base-url:}") String baseUrl,
      @Value("${bank-forecast.llm.api-key:}") String apiKey,
      @Value("${bank-forecast.llm.model:}") String model,
      RestTemplate llmRestTemplate) {
    this.enabled = enabled;
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model == null ? "" : model.trim();
    this.restTemplate = llmRestTemplate;
  }

  public boolean available() {
    return enabled && !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
  }

  /** 调用大模型；不可用或失败时返回 empty（不抛出，由调用方降级）。 */
  public Optional<String> complete(String systemPrompt, String userPrompt) {
    if (!available()) return Optional.empty();
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);
      java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
      Map<String, String> systemMessage = new java.util.HashMap<>();
      systemMessage.put("role", "system");
      systemMessage.put("content", systemPrompt);
      messages.add(systemMessage);
      Map<String, String> userMessage = new java.util.HashMap<>();
      userMessage.put("role", "user");
      userMessage.put("content", userPrompt);
      messages.add(userMessage);
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("model", model);
      body.put("messages", messages);
      body.put("temperature", 0);
      ResponseEntity<Map> response = restTemplate.postForEntity(
          baseUrl.replaceAll("/+$", "") + "/chat/completions",
          new HttpEntity<>(body, headers), Map.class);
      Map<?, ?> responseBody = response.getBody();
      if (responseBody == null) return Optional.empty();
      Object choices = responseBody.get("choices");
      if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) return Optional.empty();
      Object first = ((List<?>) choices).get(0);
      if (!(first instanceof Map)) return Optional.empty();
      Object message = ((Map<?, ?>) first).get("message");
      if (!(message instanceof Map)) return Optional.empty();
      Object content = ((Map<?, ?>) message).get("content");
      return content == null ? Optional.empty() : Optional.of(String.valueOf(content).trim());
    } catch (Exception ex) {
      LOGGER.warn("大模型调用失败，调用方将降级为规则实现：{}", ex.getMessage());
      return Optional.empty();
    }
  }
}
