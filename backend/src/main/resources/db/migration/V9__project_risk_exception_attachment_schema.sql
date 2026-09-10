create table exception_attachment (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  exception_case_id bigint not null,
  file_name varchar(256) not null,
  content_type varchar(128) not null,
  file_size bigint not null,
  file_content blob not null,
  uploaded_by bigint null,
  created_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

create index idx_exception_attachment_tenant_case on exception_attachment (tenant_id, exception_case_id);
