create table bank_connection (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  bank_code varchar(64) not null,
  bank_name varchar(128) not null,
  connection_type varchar(32) not null default 'file',
  bank_account_id bigint null,
  status varchar(32) not null default 'active',
  last_tested_at timestamp null,
  last_test_status varchar(32) null,
  last_test_message varchar(512) null,
  remark varchar(512) null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_tenant_bank_connection unique (tenant_id, bank_code, bank_account_id)
);

create index idx_bank_connection_tenant on bank_connection (tenant_id, status);
