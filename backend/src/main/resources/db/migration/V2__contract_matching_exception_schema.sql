create table project (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  project_no varchar(128) not null,
  project_name varchar(256) not null,
  customer_name varchar(256) null,
  project_manager varchar(128) null,
  project_status varchar(32) not null default 'active',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_project_no unique (tenant_id, project_no)
);

create table contract (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  contract_no varchar(128) not null,
  contract_name varchar(256) not null,
  customer_name varchar(256) not null,
  project_id bigint null,
  project_no varchar(128) null,
  project_name varchar(256) null,
  contract_amount decimal(18,2) not null,
  sign_date date null,
  start_date date null,
  end_date date null,
  status varchar(32) not null default 'active',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_contract_no unique (tenant_id, contract_no)
);

create table contract_receivable_plan (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  contract_id bigint not null,
  project_id bigint null,
  node_name varchar(128) not null,
  node_type varchar(32) not null,
  due_date date not null,
  plan_amount decimal(18,2) not null,
  paid_amount decimal(18,2) not null default 0,
  status varchar(32) not null default 'unpaid',
  owner_user_id bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
  ,constraint uk_contract_receivable_node unique (tenant_id, contract_id, node_name)
);

create index idx_receivable_tenant_due_date on contract_receivable_plan (tenant_id, due_date);
create index idx_receivable_tenant_status on contract_receivable_plan (tenant_id, status);

create table match_job (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  job_type varchar(32) not null,
  scope_type varchar(32) not null,
  status varchar(32) not null,
  started_at timestamp null,
  finished_at timestamp null,
  summary_json text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create table match_result (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  match_job_id bigint not null,
  bank_transaction_id bigint not null,
  contract_id bigint null,
  contract_receivable_plan_id bigint null,
  project_id bigint null,
  match_type varchar(32) not null,
  confidence_level varchar(16) not null,
  match_status varchar(32) not null,
  match_reason varchar(512) not null,
  confirmed_by bigint null,
  confirmed_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_match_result_tenant_txn on match_result (tenant_id, bank_transaction_id);
create index idx_match_result_tenant_plan on match_result (tenant_id, contract_receivable_plan_id);

create table exception_case (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  exception_no varchar(128) not null,
  exception_type varchar(32) not null,
  source_type varchar(32) not null,
  source_id bigint not null,
  title varchar(256) not null,
  description text not null,
  owner_user_id bigint null,
  status varchar(32) not null default 'new',
  severity varchar(16) not null default 'medium',
  due_date date null,
  closed_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_exception_source unique (tenant_id, exception_type, source_type, source_id)
);

create index idx_exception_tenant_type_status on exception_case (tenant_id, exception_type, status);

create table exception_action_log (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  exception_case_id bigint not null,
  action_type varchar(32) not null,
  action_by bigint null,
  action_text text null,
  action_at timestamp not null default current_timestamp
);
