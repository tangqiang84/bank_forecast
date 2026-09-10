create table finance_record (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  record_no varchar(128) not null,
  record_type varchar(32) not null,
  record_date date not null,
  posting_date date null,
  counterparty_name varchar(256) null,
  amount decimal(18,2) not null,
  summary varchar(512) null,
  source_system varchar(64) null,
  status varchar(32) not null default 'active',
  created_by bigint null,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_finance_record_tenant_type_date on finance_record (tenant_id, record_type, record_date);
create index idx_finance_record_tenant_no on finance_record (tenant_id, record_no);
alter table match_result add finance_record_id bigint null;
alter table exception_case add reconciliation_job_id bigint null;
create index idx_exception_reconciliation_job on exception_case (tenant_id, reconciliation_job_id);
create index idx_match_result_tenant_finance on match_result (tenant_id, finance_record_id);
