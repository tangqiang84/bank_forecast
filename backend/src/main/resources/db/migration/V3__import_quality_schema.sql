create table import_row_error (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  import_job_id bigint not null,
  row_no int not null,
  field_name varchar(128) null,
  raw_json text not null,
  error_message varchar(1024) not null,
  created_at timestamp not null default current_timestamp
);

create index idx_import_row_error_job_row on import_row_error (tenant_id, import_job_id, row_no);

alter table import_job add skipped_rows int not null default 0;
