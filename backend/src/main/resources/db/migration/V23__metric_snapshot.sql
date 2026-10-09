-- P2-4 试点数据采集：指标快照和历史趋势
create table metric_snapshot (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  snapshot_date date not null,
  metric_code varchar(64) not null,
  metric_value decimal(18,4) null,
  detail_json text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_tenant_metric_snapshot unique (tenant_id, snapshot_date, metric_code)
);

create index idx_metric_snapshot_tenant_date on metric_snapshot (tenant_id, snapshot_date);

-- 记录 XLSX 导入/预览任务识别出的银行模板数，用于模板识别成功率口径
alter table import_job add column templates_recognized int null;
