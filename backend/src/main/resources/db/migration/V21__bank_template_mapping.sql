create table bank_template_mapping (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  header varchar(128) not null,
  field varchar(64) not null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_tenant_template_mapping unique (tenant_id, header)
);

create index idx_bank_template_mapping_tenant on bank_template_mapping (tenant_id);

insert into permission (permission_code, permission_name, module) values
  ('template:manage', '银行模板映射配置', 'template');
