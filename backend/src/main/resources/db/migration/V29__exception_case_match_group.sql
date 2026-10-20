-- 回款处理工作台（P4-2）：异常工单承载匹配组
-- match_group_id 对齐 match_result.match_group_id（varchar(64)）；
-- 唯一索引同时承担索引与防重复建单约束，MySQL 唯一索引允许多个 NULL，存量行不受影响
alter table exception_case add column match_group_id varchar(64) null;
create unique index uk_exception_tenant_match_group on exception_case (tenant_id, match_group_id);
