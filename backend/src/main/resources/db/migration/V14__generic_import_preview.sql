create table import_preview_item (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  job_id bigint not null,
  row_no int not null,
  status varchar(16) not null,
  payload_json text null,
  error_message varchar(1024) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create index idx_import_preview_item_job on import_preview_item (job_id);
create index idx_import_preview_item_tenant_job_status on import_preview_item (tenant_id, job_id, status, row_no);
