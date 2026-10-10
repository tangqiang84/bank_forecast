-- 对手方别名归一（P3-6）：租户级别名 → 标准名称，匹配运行时应用
create table counterparty_alias (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  alias varchar(256) not null,
  canonical_name varchar(256) not null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  constraint uk_tenant_counterparty_alias unique (tenant_id, alias)
);

create index idx_counterparty_alias_tenant on counterparty_alias (tenant_id);
