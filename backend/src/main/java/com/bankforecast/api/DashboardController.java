package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  @GetMapping("/overview")
  public ApiResponse<Map<String, Object>> overview() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_balance", new BigDecimal("2865300.00"));
    data.put("yesterday_net_inflow", new BigDecimal("128400.00"));
    data.put("pending_exceptions", 7);
    data.put("idle_accounts", 3);
    data.put("match_rate", 0.78);
    data.put("last_sync_at", "2026-09-09T08:30:00+08:00");

    List<Map<String, Object>> topReceivables = new ArrayList<>();
    topReceivables.add(receivable("启明科技项目三期", "ACME 客户", new BigDecimal("126000.00"), "2026-09-10", "待收"));
    topReceivables.add(receivable("星链平台续费", "星链信息", new BigDecimal("98000.00"), "2026-09-12", "待收"));
    topReceivables.add(receivable("云迁移二期", "远航数科", new BigDecimal("86000.00"), "2026-09-15", "部分收款"));
    data.put("top_receivables", topReceivables);

    List<Map<String, Object>> recentImportJobs = new ArrayList<>();
    recentImportJobs.add(importJob("银行流水导入", "running", 1200, 980, 220, "处理中"));
    recentImportJobs.add(importJob("合同应收导入", "success", 86, 86, 0, "已完成"));
    recentImportJobs.add(importJob("财务记录导入", "failed", 42, 28, 14, "第 14 行日期格式错误"));
    data.put("recent_import_jobs", recentImportJobs);

    List<Map<String, Object>> keyRisks = new ArrayList<>();
    keyRisks.add(risk("应收未收", 5, "超过 7 天未到账的合同节点"));
    keyRisks.add(risk("账户闲置", 3, "近 180 天无动账账户"));
    keyRisks.add(risk("未知收款", 2, "未匹配到合同或项目的到账流水"));
    data.put("key_risks", keyRisks);

    return ApiResponse.ok(data);
  }

  private Map<String, Object> receivable(String contractName, String customerName,
      BigDecimal amount, String dueDate, String status) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("contract_name", contractName);
    item.put("customer_name", customerName);
    item.put("amount", amount);
    item.put("due_date", dueDate);
    item.put("status", status);
    return item;
  }

  private Map<String, Object> importJob(String name, String status, int totalRows,
      int successRows, int failedRows, String message) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", name);
    item.put("status", status);
    item.put("total_rows", totalRows);
    item.put("success_rows", successRows);
    item.put("failed_rows", failedRows);
    item.put("message", message);
    return item;
  }

  private Map<String, Object> risk(String title, int count, String description) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("title", title);
    item.put("count", count);
    item.put("description", description);
    return item;
  }
}
