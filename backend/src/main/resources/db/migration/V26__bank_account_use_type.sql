-- 账户用途（账户用途分布和销户建议数据源；字段字典既有规划 use_type）
alter table bank_account add column use_type varchar(32) null;
