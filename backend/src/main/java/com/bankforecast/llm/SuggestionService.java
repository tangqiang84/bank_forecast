package com.bankforecast.llm;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 流水分类标签自动建议（P3-5）和异常原因解释草稿（P3-9 应用场景）。
 * 大模型可用时由大模型生成（结果按白名单校验），不可用时降级为关键词规则。
 * 建议仅供参考，正式分类仍以人工确认为准（PRD §9 边界）。
 */
@Service
public class SuggestionService {

  public static final List<String> CATEGORIES = Arrays.asList(
      "货款", "服务费", "工资", "报销", "税费", "保证金", "退款", "内部转账", "其他");

  private static final String CATEGORY_PROMPT =
      "你是企业资金流水分类助手。把给定的银行流水摘要和对方户名分类到以下类别之一：货款、服务费、工资、报销、税费、保证金、退款、内部转账、其他。只输出类别名称，不要输出其他内容。";

  private final JdbcTemplate jdbcTemplate;
  private final LlmClient llmClient;

  public SuggestionService(JdbcTemplate jdbcTemplate, LlmClient llmClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.llmClient = llmClient;
  }

  /** 流水分类建议：优先大模型，降级关键词规则。 */
  public Map<String, Object> suggestCategory(Long transactionId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select direction, counterparty_name, summary, category from bank_transaction where id = ? and tenant_id = ? and deleted_at is null",
        transactionId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流水不存在");
    Map<String, Object> row = rows.get(0);
    String summary = text(row.get("summary"));
    String counterparty = text(row.get("counterparty_name"));
    String direction = text(row.get("direction"));

    Optional<String> llmResult = llmClient.complete(CATEGORY_PROMPT,
        "摘要：" + summary + "；对方户名：" + counterparty);
    Map<String, Object> result = new LinkedHashMap<>();
    if (llmResult.isPresent() && CATEGORIES.contains(llmResult.get())) {
      result.put("suggested_category", llmResult.get());
      result.put("source", "llm");
      result.put("reason", "大模型根据摘要和对方户名生成");
    } else {
      result.put("suggested_category", ruleCategory(direction, summary, counterparty));
      result.put("source", "rule");
      result.put("reason", "关键词规则匹配" + (llmClient.available() ? "（大模型结果非法，已降级）" : "（未配置大模型）"));
    }
    result.put("current_category", row.get("category"));
    return result;
  }

  /** 异常原因解释草稿：大模型生成或模板拼装，仅为草稿供人工参考。 */
  public Map<String, Object> explainException(Long exceptionId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select exception_no, exception_type, title, description, severity from exception_case where id = ? and tenant_id = ? and deleted_at is null",
        exceptionId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "异常不存在");
    Map<String, Object> row = rows.get(0);
    String title = text(row.get("title"));
    String description = text(row.get("description"));
    String type = text(row.get("exception_type"));

    Map<String, Object> result = new LinkedHashMap<>();
    Optional<String> llmResult = llmClient.complete(
        "你是企业资金异常分析助手。根据异常标题和描述，用两到三句中文起草异常原因解释，语气客观，不要编造数据。",
        "异常类型：" + type + "；标题：" + title + "；描述：" + description);
    if (llmResult.isPresent() && !llmResult.get().isEmpty()) {
      result.put("draft", llmResult.get());
      result.put("source", "llm");
    } else {
      result.put("draft", "异常「" + title + "」（类型 " + type + "）：" + description
          + "。请结合关联流水、合同应收节点和财务记录核实业务事实后处理。");
      result.put("source", "rule");
    }
    return result;
  }

  private String ruleCategory(String direction, String summary, String counterparty) {
    String text = summary + counterparty;
    if ("transfer".equals(direction)) return "内部转账";
    if (text.contains("退款") || text.contains("退回")) return "退款";
    if (text.contains("工资") || text.contains("社保") || text.contains("公积金")) return "工资";
    if (text.contains("报销") || text.contains("差旅")) return "报销";
    if (text.contains("税") || text.contains("增值税")) return "税费";
    if (text.contains("保证金") || text.contains("押金")) return "保证金";
    if (text.contains("服务费") || text.contains("咨询") || text.contains("技术服务")) return "服务费";
    if (text.contains("货款") || text.contains("采购") || text.contains("销售") || text.contains("回款")) return "货款";
    return "其他";
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
