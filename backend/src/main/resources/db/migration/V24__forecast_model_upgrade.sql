-- 分析服务模型层重构：注册 v2/v3 模型版本，预测结果支持置信区间，任务记录回测指标
-- v1 保持 active（向后兼容），v2/v3 默认 inactive，可在模型管理中启用；
-- 可用性以 analytics 模型注册表为准，本表记录租户侧默认选择。
insert into forecast_model_version (version, model_name, status, config_json)
values
  ('v2', 'weekly-seasonal-naive', 'inactive', '{"algorithm":"weekly-seasonal-naive","description":"星期几分组中位数 + 中位数稳健趋势，抗离群值"}'),
  ('v3', 'holt-linear', 'inactive', '{"algorithm":"holt-linear","description":"Holt 双参数指数平滑，参数网格回测择优"}');

alter table forecast_result add column ci_lower decimal(18,2) null;
alter table forecast_result add column ci_upper decimal(18,2) null;
alter table forecast_job add column backtest_json text null;
