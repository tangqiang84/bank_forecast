-- 应付计划（驾驶舱未来应付 Top 5 和资金缺口数据源）
create table contract_payable_plan (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  contract_id bigint null,
  supplier_name varchar(256) not null,
  node_name varchar(128) not null,
  due_date date not null,
  plan_amount decimal(18,2) not null,
  paid_amount decimal(18,2) not null default 0,
  status varchar(32) not null default 'unpaid',
  import_job_id bigint null,
  remark varchar(512) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_payable_plan_tenant_due on contract_payable_plan (tenant_id, status, due_date);
create index idx_payable_plan_tenant_contract on contract_payable_plan (tenant_id, contract_id);
