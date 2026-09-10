create table import_preview_row (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  import_job_id bigint not null,
  bank_account_id bigint not null,
  row_no int not null,
  raw_json text not null,
  transaction_no varchar(128) null,
  transaction_date date null,
  direction varchar(16) null,
  amount decimal(18,2) null,
  balance_after decimal(18,2) null,
  counterparty_name varchar(256) null,
  summary varchar(512) null,
  status varchar(32) not null,
  error_message varchar(1024) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_import_preview_row_job_status on import_preview_row (tenant_id, import_job_id, status, row_no);
create unique index uk_import_preview_row_job_row on import_preview_row (tenant_id, import_job_id, row_no);

alter table import_job add column preview_confirmed_at timestamp null;
