-- P2-3 导入质量：弱指纹去重
-- weak_fingerprint = 账户（隐含在表上按 bank_account_id 过滤）+ 日期 + 方向 + 金额(2dp) + 归一化对手方 + 归一化摘要
-- 回填说明：存量行按 SQL 规则回填（lower+trim），与 Java 规则（另含连续空白折叠）差异可忽略；
-- 金额 cast 为 decimal(18,2) 文本（如 100.10），与 Java setScale(2).toPlainString() 一致。
alter table bank_transaction add column weak_fingerprint varchar(512) null;

update bank_transaction
set weak_fingerprint = lower(concat_ws('|',
    cast(transaction_date as varchar),
    direction,
    cast(amount as varchar),
    trim(coalesce(counterparty_name, '')),
    trim(coalesce(summary, ''))));

create index idx_bank_transaction_weak_fp on bank_transaction (tenant_id, bank_account_id, weak_fingerprint);

alter table import_preview_row add column weak_fingerprint varchar(512) null;

alter table import_job add column duplicate_suspected_rows int not null default 0;
