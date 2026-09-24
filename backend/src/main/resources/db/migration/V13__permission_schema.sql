create table permission (
  id bigint auto_increment primary key,
  permission_code varchar(128) not null,
  permission_name varchar(128) not null,
  module varchar(64) not null,
  status varchar(32) not null default 'active',
  created_at timestamp not null default current_timestamp,
  constraint uk_permission_code unique (permission_code)
);

create table role_permission (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  role_id bigint not null,
  permission_id bigint not null,
  created_at timestamp not null default current_timestamp,
  constraint uk_role_permission unique (tenant_id, role_id, permission_id)
);

create table user_project_scope (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  user_id bigint not null,
  project_id bigint not null,
  created_at timestamp not null default current_timestamp,
  constraint uk_user_project_scope unique (tenant_id, user_id, project_id)
);

insert into permission (permission_code, permission_name, module) values
  ('dashboard:view', '查看驾驶舱', 'dashboard'),
  ('account:view', '查看银行账户', 'account'),
  ('account:manage', '维护银行账户', 'account'),
  ('account:scan', '闲置账户盘点', 'account'),
  ('transaction:view', '查看银行流水', 'transaction'),
  ('transaction:import', '导入银行流水', 'transaction'),
  ('transaction:export', '导出银行流水', 'transaction'),
  ('import:view', '查看导入任务', 'import'),
  ('contract:view', '查看合同应收', 'contract'),
  ('contract:import', '导入合同应收', 'contract'),
  ('project:view', '查看项目资金', 'project'),
  ('project:manage', '维护项目', 'project'),
  ('project:rule', '配置项目风险规则', 'project'),
  ('matching:view', '查看匹配结果', 'matching'),
  ('matching:run', '运行回款匹配', 'matching'),
  ('matching:confirm', '确认或拒绝匹配', 'matching'),
  ('exception:view', '查看异常事项', 'exception'),
  ('exception:assign', '分派异常事项', 'exception'),
  ('exception:handle', '处理异常事项', 'exception'),
  ('attachment:view', '查看异常附件', 'attachment'),
  ('attachment:manage', '上传删除异常附件', 'attachment'),
  ('reconciliation:view', '查看财务对账', 'reconciliation'),
  ('reconciliation:run', '运行财务对账和财务记录导入', 'reconciliation'),
  ('report:view', '查看报表任务', 'report'),
  ('report:generate', '生成报表', 'report'),
  ('report:download', '下载报表', 'report'),
  ('forecast:view', '查看现金预测', 'forecast'),
  ('forecast:run', '运行现金预测', 'forecast'),
  ('forecast:model', '管理预测模型版本', 'forecast'),
  ('audit:view', '查看审计日志', 'audit');
