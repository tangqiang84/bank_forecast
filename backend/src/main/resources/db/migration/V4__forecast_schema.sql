create table forecast_job (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  status varchar(32) not null,
  model_name varchar(128) null,
  horizon int not null,
  window_size int not null,
  input_start_date date null,
  input_end_date date null,
  attempt_count int not null default 0,
  error_message varchar(1024) null,
  started_at timestamp null,
  finished_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_forecast_job_tenant_status on forecast_job (tenant_id, status, id);

create table forecast_result (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  forecast_job_id bigint not null,
  forecast_date date not null,
  forecast_amount decimal(18,2) not null,
  expected_receivable decimal(18,2) not null default 0,
  projected_balance decimal(18,2) not null,
  actual_amount decimal(18,2) null,
  deviation_amount decimal(18,2) null,
  risk_level varchar(16) not null,
  risk_message varchar(512) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_forecast_job_date unique (forecast_job_id, forecast_date)
);

create index idx_forecast_result_tenant_date on forecast_result (tenant_id, forecast_date);
