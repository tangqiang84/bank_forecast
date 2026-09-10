create table report_task (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  report_type varchar(32) not null,
  date_from date null,
  date_to date null,
  status varchar(32) not null,
  file_name varchar(256) null,
  result_json text null,
  file_content text null,
  error_message varchar(1024) null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_report_task_tenant_type_status on report_task (tenant_id, report_type, status);
create index idx_report_task_tenant_created on report_task (tenant_id, created_at);
