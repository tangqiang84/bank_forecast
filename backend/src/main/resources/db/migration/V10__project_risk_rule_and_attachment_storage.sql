create table project_risk_rule (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  rule_code varchar(64) not null,
  threshold decimal(18,6) not null default 0,
  penalty decimal(18,2) not null default 0,
  max_penalty decimal(18,2) null,
  enabled boolean not null default true,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_project_risk_rule_tenant_code unique (tenant_id, rule_code)
);

alter table exception_attachment add column storage_backend varchar(32) not null default 'database';
alter table exception_attachment add column object_key varchar(512) null;

create index idx_project_risk_rule_tenant_enabled on project_risk_rule (tenant_id, enabled);
