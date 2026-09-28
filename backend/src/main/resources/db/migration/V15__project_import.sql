alter table project add start_date date null;
alter table project add delivery_date date null;
alter table project add acceptance_date date null;
alter table project add remark varchar(512) null;

insert into permission (permission_code, permission_name, module) values
  ('project:import', '导入项目', 'project');
