create table project_risk_rule_version (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  rule_code varchar(64) not null,
  threshold decimal(18,6) not null default 0,
  penalty decimal(18,2) not null default 0,
  max_penalty decimal(18,2) null,
  enabled boolean not null default true,
  version_no int not null,
  change_source varchar(32) not null default 'manual',
  remark varchar(512) null,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp
);

create index idx_rule_version_tenant_code on project_risk_rule_version (tenant_id, rule_code, version_no);

create table tenant_rule_config (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  config_key varchar(64) not null,
  config_value varchar(256) not null,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_tenant_rule_config unique (tenant_id, config_key)
);
