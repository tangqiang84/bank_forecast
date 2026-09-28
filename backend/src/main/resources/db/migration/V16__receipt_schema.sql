create table receipt (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  import_job_id bigint null,
  bank_transaction_id bigint null,
  bank_account_id bigint null,
  bank_name varchar(128) null,
  receipt_no varchar(128) not null,
  print_date date null,
  transaction_date date not null,
  transaction_time varchar(16) null,
  currency varchar(16) not null default 'CNY',
  payer_name varchar(256) null,
  payer_account_last4 varchar(8) null,
  payee_name varchar(256) null,
  payee_account_last4 varchar(8) null,
  payer_bank varchar(256) null,
  payee_bank varchar(256) null,
  amount decimal(18,2) not null,
  summary varchar(512) null,
  transaction_no varchar(128) null,
  channel varchar(64) null,
  verification_code varchar(128) null,
  image_file_name varchar(256) null,
  image_content_type varchar(128) null,
  image_size bigint null,
  image_object_key varchar(512) null,
  image_uploaded_by bigint null,
  image_uploaded_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_receipt_no unique (tenant_id, receipt_no)
);

create index idx_receipt_tenant_transaction on receipt (tenant_id, bank_transaction_id);
create index idx_receipt_tenant_job on receipt (tenant_id, import_job_id);

insert into permission (permission_code, permission_name, module) values
  ('receipt:view', '查看和下载回单', 'receipt'),
  ('receipt:import', '导入回单和上传影像', 'receipt');
