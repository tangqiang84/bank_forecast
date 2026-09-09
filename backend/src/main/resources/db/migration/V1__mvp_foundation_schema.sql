create table tenant (
  id bigint auto_increment primary key,
  tenant_code varchar(64) not null,
  tenant_name varchar(128) not null,
  status varchar(32) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_code unique (tenant_code)
);

create table user_account (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  login_name varchar(64) not null,
  display_name varchar(64) not null,
  password_hash varchar(255) not null,
  status varchar(32) not null,
  last_login_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_login_name unique (tenant_id, login_name)
);

create table role (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  role_code varchar(64) not null,
  role_name varchar(64) not null,
  status varchar(32) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_role_code unique (tenant_id, role_code)
);

create table user_role (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  user_id bigint not null,
  role_id bigint not null,
  created_at timestamp not null default current_timestamp,
  constraint uk_user_role unique (tenant_id, user_id, role_id)
);

create table bank_account (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  bank_code varchar(64) not null,
  bank_name varchar(128) not null,
  account_name varchar(128) not null,
  account_no_cipher varchar(256) not null,
  account_no_last4 varchar(8) not null,
  currency varchar(16) not null,
  status varchar(32) not null,
  current_balance decimal(18,2) not null default 0,
  last_transaction_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_bank_account_tenant_status on bank_account (tenant_id, status);

create table import_job (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  job_type varchar(32) not null,
  source_type varchar(32) not null,
  file_name varchar(256) not null,
  status varchar(32) not null,
  total_rows int not null default 0,
  success_rows int not null default 0,
  failed_rows int not null default 0,
  error_message text null,
  started_at timestamp null,
  finished_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_import_job_tenant_type_status on import_job (tenant_id, job_type, status);

create table bank_statement_raw (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  import_job_id bigint not null,
  bank_account_id bigint not null,
  row_no int not null,
  raw_json text not null,
  parse_status varchar(32) not null,
  parse_error text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_bank_statement_raw_job_row on bank_statement_raw (import_job_id, row_no);

create table bank_transaction (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  bank_account_id bigint not null,
  import_job_id bigint not null,
  transaction_no varchar(128) not null,
  transaction_date date not null,
  booking_date date null,
  direction varchar(16) not null,
  amount decimal(18,2) not null,
  balance_after decimal(18,2) null,
  counterparty_name varchar(256) null,
  counterparty_account_cipher varchar(256) null,
  summary varchar(512) null,
  purpose varchar(256) null,
  category varchar(64) null,
  match_status varchar(32) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_account_txn_no unique (tenant_id, bank_account_id, transaction_no)
);

create index idx_bank_transaction_tenant_date on bank_transaction (tenant_id, transaction_date);
create index idx_bank_transaction_match_status on bank_transaction (tenant_id, match_status);

create table audit_log (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  user_id bigint null,
  action varchar(64) not null,
  target_type varchar(64) not null,
  target_id varchar(64) null,
  trace_id varchar(64) not null,
  detail text null,
  created_at timestamp not null default current_timestamp
);

create index idx_audit_log_tenant_action on audit_log (tenant_id, action);
